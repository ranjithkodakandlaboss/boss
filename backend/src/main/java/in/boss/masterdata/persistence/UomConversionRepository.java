package in.boss.masterdata.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UomConversionRepository extends JpaRepository<UomConversionEntity, UUID> {
    List<UomConversionEntity> findByTenantId(UUID tenantId);
    Optional<UomConversionEntity> findByTenantIdAndFromUomIdAndToUomId(UUID tenantId, UUID fromUomId, UUID toUomId);
}
