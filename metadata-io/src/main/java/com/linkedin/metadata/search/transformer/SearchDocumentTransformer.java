package com.linkedin.metadata.search.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.schema.DataSchema;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.entity.client.SystemEntityClient;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.entity.EntityUtils;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.SearchScoreFieldSpec;
import com.linkedin.metadata.models.SearchableFieldSpec;
import com.linkedin.metadata.models.SearchableRefFieldSpec;

import com.linkedin.metadata.models.annotation.SearchableAnnotation.FieldType;
import com.linkedin.metadata.models.extractor.FieldExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.linkedin.metadata.models.registry.EntityRegistry;
import com.linkedin.r2.RemoteInvocationException;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;


/**
 * Class that provides a utility function that transforms the snapshot object into a search document
 */
@Slf4j
@Setter
@RequiredArgsConstructor
public class SearchDocumentTransformer {

  // Number of elements to index for a given array.
  // The cap improves search speed when having fields with a large number of elements
  private final int maxArrayLength;

  private final int maxObjectKeys;

  // Maximum customProperties value length
  private final int maxValueLength;

  private SystemEntityClient entityClient;
  private EntityRegistry entityRegistry;
   private static final String BROWSE_PATH_V2_DELIMITER = "␟";

  public Optional<String> transformSnapshot(final RecordTemplate snapshot, final EntitySpec entitySpec,
      final Boolean forDelete) {
    final Map<SearchableFieldSpec, List<Object>> extractedSearchableFields =
        FieldExtractor.extractFieldsFromSnapshot(snapshot, entitySpec, AspectSpec::getSearchableFieldSpecs, maxValueLength).entrySet()
                // Delete expects urn to be preserved
                .stream().filter(entry -> !forDelete || !"urn".equals(entry.getKey().getSearchableAnnotation().getFieldName()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    final Map<SearchScoreFieldSpec, List<Object>> extractedSearchScoreFields =
        FieldExtractor.extractFieldsFromSnapshot(snapshot, entitySpec, AspectSpec::getSearchScoreFieldSpecs, maxValueLength);
    if (extractedSearchableFields.isEmpty() && extractedSearchScoreFields.isEmpty()) {
      return Optional.empty();
    }
    final ObjectNode searchDocument = JsonNodeFactory.instance.objectNode();
    searchDocument.put("urn", snapshot.data().get("urn").toString());
    extractedSearchableFields.forEach((key, value) -> setSearchableValue(key, value, searchDocument, forDelete));
    extractedSearchScoreFields.forEach((key, values) -> setSearchScoreValue(key, values, searchDocument, forDelete));
    return Optional.of(searchDocument.toString());
  }

  public Optional<String> transformAspect(
      final Urn urn,
      final RecordTemplate aspect,
      final AspectSpec aspectSpec,
      final Boolean forDelete) {
    final Map<SearchableFieldSpec, List<Object>> extractedSearchableFields =
        FieldExtractor.extractFields(aspect, aspectSpec.getSearchableFieldSpecs(), maxValueLength);
    final Map<SearchScoreFieldSpec, List<Object>> extractedSearchScoreFields =
        FieldExtractor.extractFields(aspect, aspectSpec.getSearchScoreFieldSpecs(), maxValueLength);
    final Map<SearchableRefFieldSpec, List<Object>> extractedSearchableRefFields =
            FieldExtractor.extractFields(aspect, aspectSpec.getSearchableRefFieldSpecs(), maxValueLength);
    Optional<String> result = Optional.empty();
    if (!extractedSearchableFields.isEmpty() || !extractedSearchScoreFields.isEmpty() || !extractedSearchableRefFields.isEmpty()) {
      final ObjectNode searchDocument = JsonNodeFactory.instance.objectNode();
      searchDocument.put("urn", urn.toString());
      extractedSearchableFields.forEach((key, values) -> setSearchableValue(key, values, searchDocument, forDelete));
      extractedSearchScoreFields.forEach((key, values) -> setSearchScoreValue(key, values, searchDocument, forDelete));
      extractedSearchableRefFields.forEach((key, value) -> setSearchableRefValue(key, value, searchDocument, forDelete));
      result = Optional.of(searchDocument.toString());
    }
    return result;
  }
  public void setSearchableValue(final SearchableFieldSpec fieldSpec, final List<Object> fieldValues,
      final ObjectNode searchDocument, final Boolean forDelete) {
    DataSchema.Type valueType = fieldSpec.getPegasusSchema().getType();
    Optional<Object> firstValue = fieldValues.stream().findFirst();
    boolean isArray = fieldSpec.isArray();

    // Set hasValues field if exists
    fieldSpec.getSearchableAnnotation().getHasValuesFieldName().ifPresent(fieldName -> {
      if (forDelete) {
        searchDocument.set(fieldName, JsonNodeFactory.instance.booleanNode(false));
        return;
      }
      if (valueType == DataSchema.Type.BOOLEAN) {
        searchDocument.set(fieldName, JsonNodeFactory.instance.booleanNode((Boolean) firstValue.orElse(false)));
      } else {
        searchDocument.set(fieldName, JsonNodeFactory.instance.booleanNode(!fieldValues.isEmpty()));
      }
    });

    // Set numValues field if exists
    fieldSpec.getSearchableAnnotation().getNumValuesFieldName().ifPresent(fieldName -> {
      if (forDelete) {
        searchDocument.set(fieldName, JsonNodeFactory.instance.numberNode((Integer) 0));
        return;
      }
      switch (valueType) {
        case INT:
          searchDocument.set(fieldName, JsonNodeFactory.instance.numberNode((Integer) firstValue.orElse(0)));
          break;
        case LONG:
          searchDocument.set(fieldName, JsonNodeFactory.instance.numberNode((Long) firstValue.orElse(0L)));
          break;
        default:
          searchDocument.set(fieldName, JsonNodeFactory.instance.numberNode(fieldValues.size()));
          break;
      }
    });

    final String fieldName = fieldSpec.getSearchableAnnotation().getFieldName();
    final FieldType fieldType = fieldSpec.getSearchableAnnotation().getFieldType();

    if (forDelete) {
      searchDocument.set(fieldName, JsonNodeFactory.instance.nullNode());
      return;
    }

    if (isArray || (valueType == DataSchema.Type.MAP && fieldType != FieldType.OBJECT)) {
      if (fieldType == FieldType.BROWSE_PATH_V2) {
        String browsePathV2Value = getBrowsePathV2Value(fieldValues);
        searchDocument.set(fieldName, JsonNodeFactory.instance.textNode(browsePathV2Value));
      } else {
        ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
        fieldValues.subList(0, Math.min(fieldValues.size(), maxArrayLength))
            .forEach(value -> getNodeForValue(valueType, value, fieldType).ifPresent(arrayNode::add));
        searchDocument.set(fieldName, arrayNode);
      }
    } else if (valueType == DataSchema.Type.MAP) {
      ObjectNode dictDoc = JsonNodeFactory.instance.objectNode();
      fieldValues.subList(0, Math.min(fieldValues.size(), maxObjectKeys)).forEach(fieldValue -> {
        String[] keyValues = fieldValue.toString().split("=");
        String key = keyValues[0];
        String value = keyValues[1];
        dictDoc.put(key, value);
      });
      searchDocument.set(fieldName, dictDoc);
    } else if (!fieldValues.isEmpty()) {
      getNodeForValue(valueType, fieldValues.get(0), fieldType).ifPresent(node -> searchDocument.set(fieldName, node));
    }
  }

  public void setSearchScoreValue(final SearchScoreFieldSpec fieldSpec, final List<Object> fieldValues,
      final ObjectNode searchDocument, final Boolean forDelete) {
    DataSchema.Type valueType = fieldSpec.getPegasusSchema().getType();

    final String fieldName = fieldSpec.getSearchScoreAnnotation().getFieldName();

    if (forDelete) {
      searchDocument.set(fieldName, JsonNodeFactory.instance.nullNode());
      return;
    }

    if (fieldValues.isEmpty()) {
      return;
    }

    final Object fieldValue = fieldValues.get(0);
    switch (valueType) {
      case INT:
        searchDocument.set(fieldName, JsonNodeFactory.instance.numberNode((Integer) fieldValue));
        return;
      case LONG:
        searchDocument.set(fieldName, JsonNodeFactory.instance.numberNode((Long) fieldValue));
        return;
      case FLOAT:
        searchDocument.set(fieldName, JsonNodeFactory.instance.numberNode((Float) fieldValue));
        return;
      case DOUBLE:
        searchDocument.set(fieldName, JsonNodeFactory.instance.numberNode((Double) fieldValue));
        return;
      default:
        // Only the above types are supported
        throw new IllegalArgumentException(
            String.format("SearchScore fields must be a numeric type: field %s, value %s", fieldName, fieldValue));
    }
  }

  public void setSearchableRefValue(final SearchableRefFieldSpec searchableRefFieldSpec, final List<Object> fieldValues,
                                    final ObjectNode searchDocument, final Boolean forDelete) {
    DataSchema.Type valueType = searchableRefFieldSpec.getPegasusSchema().getType();
    String fieldName = searchableRefFieldSpec.getSearchableRefAnnotation().getFieldName();
    FieldType fieldType = searchableRefFieldSpec.getSearchableRefAnnotation().getFieldType();
    boolean isArray = searchableRefFieldSpec.isArray();

    if (forDelete) {
      searchDocument.set(fieldName, JsonNodeFactory.instance.nullNode());
      return;
    }
    int depth = searchableRefFieldSpec.getSearchableRefAnnotation().getDepth();
    if (isArray) {
      ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
      fieldValues.subList(0, Math.min(fieldValues.size(), maxArrayLength))
              .forEach(value -> getNodeForRef(depth, value, fieldType).ifPresent(arrayNode::add));
      searchDocument.set(fieldName, arrayNode);
    } else if (!fieldValues.isEmpty()) {
      String finalFieldName = fieldName;
      getNodeForRef(depth, fieldValues.get(0), fieldType).ifPresent(node -> searchDocument.set(finalFieldName, node));
    }
  }

  private Optional<JsonNode> getNodeForRef(final int depth, final Object fieldValue,
                                           final FieldType fieldType) {
    if (depth == 0) {
      if (fieldValue.toString().isEmpty()) {
        return Optional.empty();
      } else {
        return Optional.of(JsonNodeFactory.instance.textNode(fieldValue.toString()));
      }
    }
    if (fieldType == FieldType.URN) {
      ObjectNode resultNode = JsonNodeFactory.instance.objectNode();
      try {
        Urn eAUrn = EntityUtils.getUrnFromString(fieldValue.toString());
        if (!entityClient.exists(eAUrn)) {
          return Optional.ofNullable(JsonNodeFactory.instance.nullNode());
        }
        resultNode.set("urn", JsonNodeFactory.instance.textNode(fieldValue.toString()));
        String entityType = eAUrn.getEntityType();
        EntitySpec entitySpec = entityRegistry.getEntitySpec(entityType);
        for (Map.Entry<String, AspectSpec> mapEntry : entitySpec.getAspectSpecMap().entrySet()) {
          String aspectName = mapEntry.getKey();
          AspectSpec aspectSpec = mapEntry.getValue();
          if (!Constants.SKIP_REFERENCE_ASPECT.contains(aspectName)) {
            try {
              RecordTemplate aspectDetails = entityClient.getLatestAspect(eAUrn.toString(), aspectName);
              // Extract searchable fields and create node using getNodeForSearchable
              final Map<SearchableFieldSpec, List<Object>> extractedSearchableFields =
                      FieldExtractor.extractFields(aspectDetails, aspectSpec.getSearchableFieldSpecs(), maxValueLength);
              for (Map.Entry<SearchableFieldSpec, List<Object>> entry : extractedSearchableFields.entrySet()) {
                SearchableFieldSpec spec = entry.getKey();
                List<Object> value = entry.getValue();
                String fieldName = spec.getSearchableAnnotation().getFieldName();
                if (!value.isEmpty()) {
                  resultNode.set(fieldName, getNodeForSearchable(spec, value).get(fieldName));
                }
              }

              // Extract searchable ref fields and create node using getNodeForRef
              final Map<SearchableRefFieldSpec, List<Object>> extractedSearchableRefFields =
                      FieldExtractor.extractFields(aspectDetails, aspectSpec.getSearchableRefFieldSpecs(), maxValueLength);
              for (Map.Entry<SearchableRefFieldSpec, List<Object>> entry : extractedSearchableRefFields.entrySet()) {
                SearchableRefFieldSpec spec = entry.getKey();
                List<Object> value = entry.getValue();
                String fieldName = spec.getSearchableRefAnnotation().getFieldName();
                boolean isArray = spec.isArray();
                if (!value.isEmpty()) {
                  if (isArray) {
                    ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
                    value.subList(0, Math.min(value.size(), maxArrayLength))
                            .forEach(val -> getNodeForRef(depth - 1, val,
                                    spec.getSearchableRefAnnotation().getFieldType()).ifPresent(arrayNode::add));
                    resultNode.set(fieldName, arrayNode);
                  } else {
                    Optional<JsonNode> node = getNodeForRef(depth - 1, value.get(0),
                            spec.getSearchableRefAnnotation().getFieldType());
                    if (node.isPresent()) {
                      resultNode.set(fieldName, node.get());
                    }
                  }
                }
              }
            } catch (RemoteInvocationException e) {
              log.error("Error while fetching aspect details of {} for urn {} : {}", aspectName, eAUrn, e.getMessage());
            }
          }
        }
        return Optional.of(resultNode);
      } catch (Exception e) {
        log.error("Error while processing ref field of urn {} : {}", fieldValue, e.getMessage());
      }
    }
    return Optional.empty();
  }



  private JsonNode getNodeForSearchable(SearchableFieldSpec fieldSpec, final List<Object> fieldValues) {
    ObjectNode resultNode = JsonNodeFactory.instance.objectNode();
    DataSchema.Type valueType = fieldSpec.getPegasusSchema().getType();
    Optional<Object> firstValue = fieldValues.stream().findFirst();
    boolean isArray = fieldSpec.isArray();

// Set hasValues field if exists
    fieldSpec.getSearchableAnnotation().getHasValuesFieldName().ifPresent(fieldName -> {
      if (valueType == DataSchema.Type.BOOLEAN) {
        resultNode.set(fieldName, JsonNodeFactory.instance.booleanNode((Boolean) firstValue.orElse(false)));
      } else {
        resultNode.set(fieldName, JsonNodeFactory.instance.booleanNode(!fieldValues.isEmpty()));
      }
    });

// Set numValues field if exists
    fieldSpec.getSearchableAnnotation().getNumValuesFieldName().ifPresent(fieldName -> {
      switch (valueType) {
        case INT:
          resultNode.set(fieldName, JsonNodeFactory.instance.numberNode((Integer) firstValue.orElse(0)));
          break;
        case LONG:
          resultNode.set(fieldName, JsonNodeFactory.instance.numberNode((Long) firstValue.orElse(0L)));
          break;
        default:
          resultNode.set(fieldName, JsonNodeFactory.instance.numberNode(fieldValues.size()));
          break;
      }
    });

    final String fieldName = fieldSpec.getSearchableAnnotation().getFieldName();
    final FieldType fieldType = fieldSpec.getSearchableAnnotation().getFieldType();

    if (isArray || (valueType == DataSchema.Type.MAP && fieldType != FieldType.OBJECT)) {
      if (fieldType == FieldType.BROWSE_PATH_V2) {
        String browsePathV2Value = getBrowsePathV2Value(fieldValues);
        resultNode.set(fieldName, JsonNodeFactory.instance.textNode(browsePathV2Value));
      } else {
        ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
        fieldValues.subList(0, Math.min(fieldValues.size(), maxArrayLength))
                .forEach(value -> getNodeForValue(valueType, value, fieldType).ifPresent(arrayNode::add));
        resultNode.set(fieldName, arrayNode);
      }
    } else if (valueType == DataSchema.Type.MAP) {
      ObjectNode dictDoc = JsonNodeFactory.instance.objectNode();
      fieldValues.subList(0, Math.min(fieldValues.size(), maxObjectKeys)).forEach(fieldValue -> {
        String[] keyValues = fieldValue.toString().split("=");
        String key = keyValues[0];
        String value = keyValues[1];
        dictDoc.put(key, value);
      });
      resultNode.set(fieldName, dictDoc);
    } else if (!fieldValues.isEmpty()) {
      getNodeForValue(valueType, fieldValues.get(0), fieldType).ifPresent(node -> resultNode.set(fieldName, node));
    }
    return resultNode;
  }

  private Optional<JsonNode> getNodeForValue(final DataSchema.Type schemaFieldType, final Object fieldValue,
      final FieldType fieldType) {
    switch (schemaFieldType) {
      case BOOLEAN:
        return Optional.of(JsonNodeFactory.instance.booleanNode((Boolean) fieldValue));
      case INT:
        return Optional.of(JsonNodeFactory.instance.numberNode((Integer) fieldValue));
      case LONG:
        return Optional.of(JsonNodeFactory.instance.numberNode((Long) fieldValue));
      // By default run toString
      default:
        String value = fieldValue.toString();
        // If index type is BROWSE_PATH, make sure the value starts with a slash
        if (fieldType == FieldType.BROWSE_PATH && !value.startsWith("/")) {
          value = "/" + value;
        }
        return value.isEmpty() ? Optional.empty()
            : Optional.of(JsonNodeFactory.instance.textNode(fieldValue.toString()));
    }
  }

  /**
   * The browsePathsV2 aspect is a list of objects and the @Searchable annotation specifies a
   * list of strings that we receive. However, we want to aggregate those strings and store
   * as a single string in ElasticSearch so we can do prefix matching against it.
   */
  private String getBrowsePathV2Value(@Nonnull final List<Object> fieldValues) {
    List<String> stringValues = new ArrayList<>();
    fieldValues.subList(0, Math.min(fieldValues.size(), maxArrayLength)).forEach(value -> {
      if (value instanceof String) {
        stringValues.add((String) value);
      }
    });
    String aggregatedValue = String.join(BROWSE_PATH_V2_DELIMITER, stringValues);
    // ensure browse path v2 starts with our delimiter if it's not empty
    if (!aggregatedValue.equals("") && !aggregatedValue.startsWith(BROWSE_PATH_V2_DELIMITER)) {
      aggregatedValue = BROWSE_PATH_V2_DELIMITER + aggregatedValue;
    }
    return aggregatedValue;
  }

  public void setEntityRegistry(EntityRegistry entityRegistry) {
    this.entityRegistry = entityRegistry;
  }
}
