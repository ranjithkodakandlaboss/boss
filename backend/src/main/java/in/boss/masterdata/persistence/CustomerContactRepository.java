package in.boss.masterdata.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CustomerContactRepository extends JpaRepository<CustomerContactEntity, UUID> {
    List<CustomerContactEntity> findByTenantIdAndCustomerId(UUID tenantId, UUID customerId);
    void deleteByTenantIdAndCustomerId(UUID tenantId, UUID customerId);
}
