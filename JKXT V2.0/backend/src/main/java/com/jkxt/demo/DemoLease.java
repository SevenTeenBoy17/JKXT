package com.jkxt.demo;

import java.time.Instant;
import java.util.UUID;

/** Captured server identity. Async work must retain this exact generation. */
public record DemoLease(UUID workspaceId,UUID orgId,long generation,Instant expiresAt) {}
