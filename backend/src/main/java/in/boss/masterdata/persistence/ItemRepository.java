package in.boss.masterdata.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ItemRepository extends JpaRepository<ItemEntity, UUID>, JpaSpecificationExecutor<ItemEntity> {
    Optional<ItemEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<ItemEntity> findByTenantIdAndCode(UUID tenantId, String code);
    boolean existsByTenantIdAndCode(UUID tenantId, String code);
    boolean existsByTenantIdAndCategoryIdAndIsActiveTrue(UUID tenantId, UUID categoryId);
    Page<ItemEntity> findByTenantId(UUID tenantId, Pageable pageable);
}
