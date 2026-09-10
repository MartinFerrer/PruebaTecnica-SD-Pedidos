package com.roshka.inventory.application.port.out;

import java.util.UUID;

public interface Events {
  void append(String type, UUID aggregateId, long version, Object payload);
}
