package in.boss.masterdata.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BomLineRepository extends JpaRepository<BomLineEntity, UUID> {
    List<BomLineEntity> findByTenantIdAndBomHeaderId(UUID tenantId, UUID bomHeaderId);
    List<BomLineEntity> findByTenantIdAndBomHeaderIdOrderByLevelAscCreatedAtAsc(UUID tenantId, UUID bomHeaderId);
    boolean existsByTenantIdAndComponentItemId(UUID tenantId, UUID componentItemId);

    @Query("SELECT COUNT(l) > 0 FROM BomLineEntity l JOIN BomHeaderEntity h ON l.bomHeaderId = h.id " +
           "WHERE l.tenantId = :tenantId AND l.componentItemId = :itemId AND h.status = 'approved'")
    boolean isComponentInActiveBom(@Param("tenantId") UUID tenantId, @Param("itemId") UUID itemId);
}
