package in.boss.masterdata.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UnitOfMeasureRepository extends JpaRepository<UnitOfMeasureEntity, UUID> {
    List<UnitOfMeasureEntity> findByTenantId(UUID tenantId);
    List<UnitOfMeasureEntity> findByTenantIdAndUomType(UUID tenantId, String uomType);
    Optional<UnitOfMeasureEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<UnitOfMeasureEntity> findByTenantIdAndSymbol(UUID tenantId, String symbol);
    boolean existsByTenantIdAndSymbol(UUID tenantId, String symbol);
    List<UnitOfMeasureEntity> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
}
