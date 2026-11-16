package in.boss.masterdata.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID>, JpaSpecificationExecutor<CustomerEntity> {
    Optional<CustomerEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<CustomerEntity> findByTenantIdAndCode(UUID tenantId, String code);
    boolean existsByTenantIdAndCode(UUID tenantId, String code);
    Page<CustomerEntity> findByTenantId(UUID tenantId, Pageable pageable);
}
