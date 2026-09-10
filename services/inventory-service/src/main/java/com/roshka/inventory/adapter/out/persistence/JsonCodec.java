package com.roshka.inventory.adapter.out.persistence;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonCodec {
  public final JsonMapper mapper =
      JsonMapper.builder()
          .enable(tools.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .changeDefaultPropertyInclusion(
              v -> v.withValueInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL))
          .build();

  public String write(Object value) {
    return mapper.writeValueAsString(value);
  }

  public <T> T read(String json, Class<T> type) {
    return mapper.readValue(json, type);
  }
}
