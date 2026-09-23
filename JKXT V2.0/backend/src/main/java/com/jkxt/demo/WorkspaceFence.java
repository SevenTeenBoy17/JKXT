package com.jkxt.demo;

import java.util.function.Supplier;

public interface WorkspaceFence {
  void assertReadable(DemoLease lease);
  <T> T commit(DemoLease lease,Supplier<T> mutation);
}
