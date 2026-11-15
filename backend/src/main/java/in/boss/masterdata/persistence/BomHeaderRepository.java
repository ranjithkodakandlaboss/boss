package in.boss.masterdata.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BomHeaderRepository extends JpaRepository<BomHeaderEntity, UUID> {
    Optional<BomHeaderEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<BomHeaderEntity> findByTenantIdAndFinishedItemIdAndStatus(UUID tenantId, UUID finishedItemId, String status);
    List<BomHeaderEntity> findByTenantIdAndFinishedItemIdOrderByCreatedAtDesc(UUID tenantId, UUID finishedItemId);
    boolean existsByTenantIdAndFinishedItemIdAndStatus(UUID tenantId, UUID finishedItemId, String status);
}
