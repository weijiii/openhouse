package com.linkedin.openhouse.tables.repository;

import com.linkedin.openhouse.common.exception.InvalidSchemaEvolutionException;
import org.apache.iceberg.Schema;

/**
 * Interface to define schema evolution rules. One should provide implementation of this interface
 * to customize schema evolution rules, e.g. enforcing no column-dropping, no column-reordering,
 * etc.
 */
public interface SchemaValidator {
  void validateWriteSchema(
      Schema oldSchema, Schema newSchema, Schema updatedSchema, String tableUri)
      throws InvalidSchemaEvolutionException, IllegalArgumentException;
}
