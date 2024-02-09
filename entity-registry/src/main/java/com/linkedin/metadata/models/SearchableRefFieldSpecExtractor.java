package com.linkedin.metadata.models;

import com.google.common.collect.ImmutableSet;
import com.linkedin.data.DataMap;
import com.linkedin.data.schema.ComplexDataSchema;
import com.linkedin.data.schema.DataSchema;
import com.linkedin.data.schema.DataSchemaTraverse;
import com.linkedin.data.schema.PathSpec;
import com.linkedin.data.schema.PrimitiveDataSchema;
import com.linkedin.data.schema.annotation.SchemaVisitor;
import com.linkedin.data.schema.annotation.SchemaVisitorTraversalResult;
import com.linkedin.data.schema.annotation.TraverserContext;
import com.linkedin.metadata.models.annotation.SearchableRefAnnotation;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Implementation of {@link SchemaVisitor} responsible for extracting {@link SearchableRefFieldSpec}
 * from an aspect schema.
 */
@Slf4j
public class SearchableRefFieldSpecExtractor implements SchemaVisitor {

  private final List<SearchableRefFieldSpec> _specs = new ArrayList<>();
  private final Map<String, String> _searchRefFieldNamesToPatch = new HashMap<>();

  private static final String MAP = "map";

  public static final Map<String, Object> PRIMARY_URN_SEARCH_PROPERTIES;
  static {
    PRIMARY_URN_SEARCH_PROPERTIES = new DataMap();
    PRIMARY_URN_SEARCH_PROPERTIES.put("enableAutocomplete", "true");
    PRIMARY_URN_SEARCH_PROPERTIES.put("fieldType", "URN");
    PRIMARY_URN_SEARCH_PROPERTIES.put("boostScore", "10.0");
  }

  private static final float SECONDARY_URN_FACTOR = 0.1f;
  private static final Set<String> SECONDARY_URN_FIELD_TYPES = ImmutableSet.<String>builder()
          .add("URN")
          .add("URN_PARTIAL")
          .build();

  public List<SearchableRefFieldSpec> getSpecs() {
    return _specs;
  }

  @Override
  public void callbackOnContext(TraverserContext context, DataSchemaTraverse.Order order) {
    if (context.getEnclosingField() == null) {
      return;
    }

    if (DataSchemaTraverse.Order.PRE_ORDER.equals(order)) {

      final DataSchema currentSchema = context.getCurrentSchema().getDereferencedDataSchema();

      final Object annotationObj = getAnnotationObj(context);

      if (annotationObj != null) {
        validatePropertiesAnnotation(currentSchema, annotationObj, context.getTraversePath().toString());
        extractSearchableRefAnnotation(annotationObj, currentSchema, context);
      }
    }
  }

  private Object getAnnotationObj(TraverserContext context) {
    final DataSchema currentSchema = context.getCurrentSchema().getDereferencedDataSchema();

    // First, check properties for primary annotation definition.
    final Map<String, Object> properties = context.getEnclosingField().getProperties();
    final Object primaryAnnotationObj = properties.get(SearchableRefAnnotation.ANNOTATION_NAME);
    if (primaryAnnotationObj != null) {
      validatePropertiesAnnotation(currentSchema, primaryAnnotationObj, context.getTraversePath().toString());
    }

    // Next, check resolved properties for annotations on primitives.
    final Map<String, Object> resolvedProperties = FieldSpecUtils.getResolvedProperties(currentSchema);
    final Object resolvedAnnotationObj = resolvedProperties.get(SearchableRefAnnotation.ANNOTATION_NAME);
    return resolvedAnnotationObj;
  }

  private void extractSearchableRefAnnotation(final Object annotationObj, final DataSchema currentSchema,
      final TraverserContext context) {
    final PathSpec path = new PathSpec(context.getSchemaPathSpec());
    final Optional<PathSpec> fullPath = FieldSpecUtils.getPathSpecWithAspectName(context);
    SearchableRefAnnotation annotation =
            SearchableRefAnnotation.fromPegasusAnnotationObject(annotationObj, FieldSpecUtils.getSchemaFieldName(path),
            currentSchema.getDereferencedType(), path.toString());
    String schemaPathSpec = context.getSchemaPathSpec().toString();
    if (_searchRefFieldNamesToPatch.containsKey(annotation.getFieldName()) && !_searchRefFieldNamesToPatch.get(
        annotation.getFieldName()).equals(schemaPathSpec)) {
      // Try to use path
      String pathName = path.toString().replace('/', '_').replace("*", "");
      if (pathName.startsWith("_")) {
        pathName = pathName.replaceFirst("_", "");
      }

      if (_searchRefFieldNamesToPatch.containsKey(pathName) && !_searchRefFieldNamesToPatch.get(pathName).equals(schemaPathSpec)) {
        throw new ModelValidationException(
            String.format("Entity has multiple searchableRef fields with the same field name %s, path: %s", annotation.getFieldName(), fullPath.orElse(path)));
      } else {
        annotation = new SearchableRefAnnotation(
            pathName,
            annotation.getFieldType(),
            annotation.isQueryByDefault(),
            annotation.isEnableAutocomplete(),
            annotation.getBoostScore(),
            annotation.getDepth(),
            annotation.getFieldNameAliases());
      }
    }
    log.debug("SearchableRef annotation for field: {} : {}", schemaPathSpec, annotation);
    final SearchableRefFieldSpec fieldSpec = new SearchableRefFieldSpec(path, annotation, currentSchema);
    _specs.add(fieldSpec);
    _searchRefFieldNamesToPatch.put(annotation.getFieldName(), context.getSchemaPathSpec().toString());
  }

  @Override
  public VisitorContext getInitialVisitorContext() {
    return null;
  }

  @Override
  public SchemaVisitorTraversalResult getSchemaVisitorTraversalResult() {
    return new SchemaVisitorTraversalResult();
  }

  private Boolean isValidComplexType(final ComplexDataSchema schema) {
    return DataSchema.Type.ENUM.equals(schema.getDereferencedDataSchema().getDereferencedType())
        || DataSchema.Type.MAP.equals(schema.getDereferencedDataSchema().getDereferencedType());
  }

  private Boolean isValidPrimitiveType(final PrimitiveDataSchema schema) {
    return true;
  }

  private void validatePropertiesAnnotation(DataSchema currentSchema, Object annotationObj, String pathStr) {

    // If primitive, assume the annotation is well formed until resolvedProperties reflects it.
    if (currentSchema.isPrimitive() || currentSchema.getDereferencedType().equals(DataSchema.Type.ENUM) || currentSchema
        .getDereferencedType()
        .equals(DataSchema.Type.MAP)) {
      return;
    }

    // Required override case. If the annotation keys are not overrides, they are incorrect.
    if (!Map.class.isAssignableFrom(annotationObj.getClass())) {
      throw new ModelValidationException(String.format(
          "Failed to validate @%s annotation declared inside %s: Invalid value type provided (Expected Map)",
          SearchableRefAnnotation.ANNOTATION_NAME, pathStr));
    }

    Map<String, Object> annotationMap = (Map<String, Object>) annotationObj;

    if (annotationMap.size() == 0) {
      throw new ModelValidationException(String.format(
          "Invalid @SearchableRef Annotation at %s. Annotation placed on invalid field of type %s. Must be placed on primitive field.",
          pathStr, currentSchema.getType()));
    }

    for (String key : annotationMap.keySet()) {
      if (!key.startsWith(Character.toString(PathSpec.SEPARATOR))) {
        throw new ModelValidationException(String.format(
            "Invalid @SearchableRef Annotation at %s. Annotation placed on invalid field of type %s. Must be placed on primitive field.",
            pathStr, currentSchema.getType()));
      }
    }
  }
}
