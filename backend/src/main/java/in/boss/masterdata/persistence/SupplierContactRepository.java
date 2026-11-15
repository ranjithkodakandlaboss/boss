package in.boss.masterdata.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SupplierContactRepository extends JpaRepository<SupplierContactEntity, UUID> {
    List<SupplierContactEntity> findByTenantIdAndSupplierId(UUID tenantId, UUID supplierId);
    void deleteByTenantIdAndSupplierId(UUID tenantId, UUID supplierId);
}
