/*
 * (c) Copyright 2016 Palantir Technologies Inc. All rights reserved.
 */

package com.palantir.conjure.gen.java.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.palantir.conjure.defs.types.ConjureType;
import com.palantir.conjure.defs.types.builtin.BinaryType;
import com.palantir.conjure.defs.types.collect.ListType;
import com.palantir.conjure.defs.types.collect.MapType;
import com.palantir.conjure.defs.types.collect.OptionalType;
import com.palantir.conjure.defs.types.collect.SetType;
import com.palantir.conjure.defs.types.complex.FieldDefinition;
import com.palantir.conjure.defs.types.complex.ObjectTypeDefinition;
import com.palantir.conjure.defs.types.names.FieldName;
import com.palantir.conjure.defs.types.primitive.PrimitiveType;
import com.palantir.conjure.gen.java.ConjureAnnotations;
import com.palantir.conjure.gen.java.types.BeanGenerator.EnrichedField;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import org.apache.commons.lang3.StringUtils;

public final class BeanBuilderGenerator {
    private final TypeMapper typeMapper;
    private final ClassName builderClass;
    private final ClassName objectClass;

    private BeanBuilderGenerator(TypeMapper typeMapper, ClassName builderClass, ClassName objectClass) {
        this.typeMapper = typeMapper;
        this.builderClass = builderClass;
        this.objectClass = objectClass;
    }

    public static TypeSpec generate(
            TypeMapper typeMapper,
            ClassName objectClass,
            ClassName builderClass,
            ObjectTypeDefinition typeDef,
            boolean ignoreUnknownProperties) {

        return new BeanBuilderGenerator(typeMapper, builderClass, objectClass)
                .generate(typeDef, ignoreUnknownProperties);
    }

    private TypeSpec generate(ObjectTypeDefinition typeDef, boolean ignoreUnknownProperties) {

        Collection<EnrichedField> enrichedFields = enrichFields(typeDef.fields());
        Collection<FieldSpec> poetFields = EnrichedField.toPoetSpecs(enrichedFields);

        TypeSpec.Builder builder = TypeSpec.classBuilder("Builder")
                .addAnnotation(ConjureAnnotations.getConjureGeneratedAnnotation(BeanBuilderGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addFields(poetFields)
                .addMethod(createConstructor())
                .addMethod(createFromObject(poetFields))
                .addMethods(createSetters(enrichedFields))
                .addMethod(createBuild(poetFields));

        if (ignoreUnknownProperties) {
            builder.addAnnotation(AnnotationSpec.builder(JsonIgnoreProperties.class)
                    .addMember("ignoreUnknown", "$L", true)
                    .build());
        }

        return builder.build();
    }

    private Collection<EnrichedField> enrichFields(Map<FieldName, FieldDefinition> fields) {
        return fields.entrySet().stream()
                .map(e -> createField(e.getKey(), e.getKey().name(), e.getValue()))
                .collect(Collectors.toList());
    }

    private static MethodSpec createConstructor() {
        return MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build();
    }

    private MethodSpec createFromObject(Collection<FieldSpec> poetFields) {
        CodeBlock assignmentBlock = CodeBlocks.of(Iterables.transform(poetFields,
                poetField -> CodeBlocks.statement(
                        "$1N(other.$2N())",
                        poetField.name,
                        BeanGenerator.generateGetterName(poetField.name))));

        return MethodSpec.methodBuilder("from")
                .addModifiers(Modifier.PUBLIC)
                .returns(builderClass)
                .addParameter(objectClass, "other")
                .addCode(assignmentBlock)
                .addStatement("return this")
                .build();
    }

    private EnrichedField createField(FieldName fieldName, String jsonKey, FieldDefinition field) {
        FieldSpec.Builder spec = FieldSpec.builder(
                typeMapper.getClassName(field.type()),
                fieldName.toCase(FieldName.Case.LOWER_CAMEL_CASE).name(),
                Modifier.PRIVATE);

        if (field.type() instanceof ListType) {
            spec.initializer("new $T<>()", ArrayList.class);
        } else if (field.type() instanceof SetType) {
            spec.initializer("new $T<>()", LinkedHashSet.class);
        } else if (field.type() instanceof MapType) {
            spec.initializer("new $T<>()", LinkedHashMap.class);
        } else if (field.type() instanceof OptionalType) {
            spec.initializer("$T.empty()", asRawType(typeMapper.getClassName(field.type())));
        }
        // else no initializer

        return EnrichedField.of(jsonKey, field, spec.build());
    }

    private Iterable<MethodSpec> createSetters(Collection<EnrichedField> fields) {
        Collection<MethodSpec> setters = Lists.newArrayListWithExpectedSize(fields.size());
        for (EnrichedField field : fields) {
            setters.add(createSetter(field));
            setters.addAll(createAuxiliarySetters(field));
        }
        return setters;
    }

    private MethodSpec createSetter(EnrichedField enriched) {
        FieldSpec field = enriched.poetSpec();
        ConjureType type = enriched.conjureDef().type();
        AnnotationSpec jsonSetterAnnotation = AnnotationSpec.builder(JsonSetter.class)
                .addMember("value", "$S", enriched.jsonKey())
                .build();
        boolean shouldClearFirst = true;
        return publicSetter(enriched)
                .addParameter(widenToCollectionIfPossible(field.type, type), field.name)
                .addCode(typeAwareSet(field, type, shouldClearFirst))
                .addStatement("return this")
                .addAnnotation(jsonSetterAnnotation)
                .build();
    }

    private MethodSpec createCollectionSetter(String prefix, EnrichedField enriched) {
        FieldSpec field = enriched.poetSpec();
        ConjureType type = enriched.conjureDef().type();
        boolean shouldClearFirst = false;
        return MethodSpec.methodBuilder(prefix + StringUtils.capitalize(field.name))
                .addJavadoc(enriched.conjureDef().docs().orElse(""))
                .addModifiers(Modifier.PUBLIC)
                .returns(builderClass)
                .addParameter(widenToCollectionIfPossible(field.type, type), field.name)
                .addCode(typeAwareSet(field, type, shouldClearFirst))
                .addStatement("return this")
                .build();
    }

    private TypeName widenToCollectionIfPossible(TypeName current, ConjureType type) {
        if (type instanceof ListType) {
            TypeName typeName = typeMapper.getClassName(((ListType) type).itemType()).box();
            return ParameterizedTypeName.get(ClassName.get(Collection.class), typeName);
        }

        if (type instanceof SetType) {
            TypeName typeName = typeMapper.getClassName(((SetType) type).itemType()).box();
            return ParameterizedTypeName.get(ClassName.get(Collection.class), typeName);
        }

        return current;
    }

    private static CodeBlock typeAwareSet(FieldSpec spec, ConjureType type, boolean shouldClearFirst) {
        if (type instanceof ListType || type instanceof SetType) {
            CodeBlock addStatement = CodeBlocks.statement(
                    "this.$1N.addAll($2L)", spec.name,
                    Expressions.requireNonNull(spec.name, spec.name + " cannot be null"));
            return shouldClearFirst ? CodeBlocks.of(CodeBlocks.statement("this.$1N.clear()", spec.name), addStatement)
                    : addStatement;
        } else if (type instanceof MapType) {
            CodeBlock addStatement = CodeBlocks.statement(
                    "this.$1N.putAll($2L)", spec.name,
                    Expressions.requireNonNull(spec.name, spec.name + " cannot be null"));
            return shouldClearFirst ? CodeBlocks.of(CodeBlocks.statement("this.$1N.clear()", spec.name), addStatement)
                    : addStatement;
        } else if (type instanceof BinaryType) {
            return CodeBlock.builder()
                    .addStatement("$L", Expressions.requireNonNull(spec.name, spec.name + " cannot be null"))
                    .addStatement("this.$1N = $2T.allocate($1N.remaining()).put($1N.duplicate())",
                            spec.name,
                            ByteBuffer.class)
                    .addStatement("this.$1N.rewind()", spec.name)
                    .build();
        } else {
            CodeBlock nullCheckedValue = spec.type.isPrimitive()
                    ? CodeBlock.of("$N", spec.name) // primitive types can't be null, so no need for requireNonNull!
                    : Expressions.requireNonNull(spec.name, spec.name + " cannot be null");
            return CodeBlocks.statement("this.$1L = $2L", spec.name, nullCheckedValue);
        }
    }

    private List<MethodSpec> createAuxiliarySetters(EnrichedField enriched) {
        ConjureType type = enriched.conjureDef().type();

        if (type instanceof ListType) {
            return ImmutableList.of(
                    createCollectionSetter("addAll", enriched),
                    createItemSetter(enriched, ((ListType) type).itemType()));
        }

        if (type instanceof SetType) {
            return ImmutableList.of(
                    createCollectionSetter("addAll", enriched),
                    createItemSetter(enriched, ((SetType) type).itemType()));
        }

        if (type instanceof MapType) {
            return ImmutableList.of(
                    createCollectionSetter("putAll", enriched),
                    createMapSetter(enriched));
        }

        if (type instanceof OptionalType) {
            return ImmutableList.of(
                    createOptionalSetter(enriched));
        }

        return ImmutableList.of();
    }

    private MethodSpec createOptionalSetter(EnrichedField enriched) {
        FieldSpec field = enriched.poetSpec();
        OptionalType type = (OptionalType) enriched.conjureDef().type();
        return publicSetter(enriched)
                .addParameter(typeMapper.getClassName(type.itemType()), field.name)
                .addCode(optionalAssignmentStatement(field, type))
                .addStatement("return this")
                .build();
    }

    private CodeBlock optionalAssignmentStatement(FieldSpec field, OptionalType type) {
        if (type.itemType() instanceof PrimitiveType) {
            switch ((PrimitiveType) type.itemType()) {
                case INTEGER:
                case DOUBLE:
                case BOOLEAN:
                    return CodeBlocks.statement("this.$1N = $2T.of($1N)",
                            field.name, asRawType(typeMapper.getClassName(type)));
                case SAFELONG:
                case STRING:
                case RID:
                case BEARERTOKEN:
                default:
                    // not special
            }
        }
        return CodeBlocks.statement("this.$1N = $2T.of($3L)",
                field.name, Optional.class, Expressions.requireNonNull(field.name, field.name + " cannot be null"));
    }

    private MethodSpec createItemSetter(EnrichedField enriched, ConjureType itemType) {
        FieldSpec field = enriched.poetSpec();
        return publicSetter(enriched)
                .addParameter(typeMapper.getClassName(itemType), field.name)
                .addStatement("this.$1N.add($1N)", field.name)
                .addStatement("return this")
                .build();
    }

    private MethodSpec createMapSetter(EnrichedField enriched) {
        MapType type = (MapType) enriched.conjureDef().type();
        return publicSetter(enriched)
                .addParameter(typeMapper.getClassName(type.keyType()), "key")
                .addParameter(typeMapper.getClassName(type.valueType()), "value")
                .addStatement("this.$1N.put(key, value)", enriched.poetSpec().name)
                .addStatement("return this")
                .build();
    }

    private MethodSpec.Builder publicSetter(EnrichedField enriched) {
        return MethodSpec.methodBuilder(enriched.poetSpec().name)
                .addJavadoc(enriched.conjureDef().docs().orElse(""))
                .addModifiers(Modifier.PUBLIC)
                .returns(builderClass);
    }

    private MethodSpec createBuild(Collection<FieldSpec> fields) {
        return MethodSpec.methodBuilder("build")
                .addModifiers(Modifier.PUBLIC)
                .returns(objectClass)
                .addStatement("return new $L", Expressions.constructorCall(objectClass, fields))
                .build();
    }

    private static TypeName asRawType(TypeName type) {
        if (type instanceof ParameterizedTypeName) {
            return ((ParameterizedTypeName) type).rawType;
        }
        return type;
    }
}
