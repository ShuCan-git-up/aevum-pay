package io.aevumpay.chain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncCursorRepository extends JpaRepository<SyncCursorEntity, String> {}
