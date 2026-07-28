package com.cryptoalgo.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("tenants")
public record Tenant(@Id UUID id, String name, String slug, String status, Instant createdAt) {}
