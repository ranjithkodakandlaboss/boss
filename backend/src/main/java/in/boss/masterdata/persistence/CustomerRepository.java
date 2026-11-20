package in.boss.masterdata.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID>, JpaSpecificationExecutor<CustomerEntity> {
    Optional<CustomerEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CustomerEntity> findForUpdateByIdAndTenantId(UUID id, UUID tenantId);
    Optional<CustomerEntity> findByTenantIdAndCode(UUID tenantId, String code);
    boolean existsByTenantIdAndCode(UUID tenantId, String code);
    boolean existsByTenantIdAndGstinIgnoreCase(UUID tenantId, String gstin);
    boolean existsByTenantIdAndGstinIgnoreCaseAndIdNot(UUID tenantId, String gstin, UUID id);
    Page<CustomerEntity> findByTenantId(UUID tenantId, Pageable pageable);
}
