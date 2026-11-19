package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.UpdateItemRequest;
import in.boss.masterdata.domain.impl.ItemServiceImpl;
import in.boss.masterdata.persistence.BomHeaderRepository;
import in.boss.masterdata.persistence.BomLineRepository;
import in.boss.masterdata.persistence.HsnCodeRepository;
import in.boss.masterdata.persistence.HsnCodeEntity;
import in.boss.masterdata.persistence.ItemCategoryRepository;
import in.boss.masterdata.persistence.ItemEntity;
import in.boss.masterdata.persistence.ItemOperationalDependencies;
import in.boss.masterdata.persistence.ItemRepository;
import in.boss.masterdata.persistence.UnitOfMeasureRepository;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemLifecycleTest {

    @Mock ItemRepository itemRepo;
    @Mock UnitOfMeasureRepository uomRepo;
    @Mock ItemCategoryRepository categoryRepo;
    @Mock HsnCodeRepository hsnCodeRepo;
    @Mock BomHeaderRepository bomHeaderRepo;
    @Mock BomLineRepository bomLineRepo;
    @Mock ItemOperationalDependencies operationalDependencies;
    @Mock AuditService auditService;

    private ItemService itemService;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        itemService = new ItemServiceImpl(
                itemRepo, uomRepo, categoryRepo, hsnCodeRepo, bomHeaderRepo, bomLineRepo,
                operationalDependencies, auditService, null, null);
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), Set.of("OWNER"), null, null));
    }

    @AfterEach
    void tearDown() {
        BossContextHolder.clear();
    }

    @Test
    void deleteItem_whenFinishedItemInActiveBom_throwsLifecycleException() {
        UUID itemId = UUID.randomUUID();
        ItemEntity item = ItemEntity.builder().id(itemId).tenantId(tenantId).isActive(true).build();
        when(itemRepo.findLockedByIdAndTenantId(itemId, tenantId)).thenReturn(Optional.of(item));
        when(bomHeaderRepo.existsByTenantIdAndFinishedItemIdAndStatus(tenantId, itemId, "approved")).thenReturn(true);

        assertThatThrownBy(() -> itemService.deleteItem(itemId))
                .isInstanceOf(LifecycleException.class)
                .hasMessageContaining("referenced as finished item in active BOM");
    }

    @Test
    void deleteItem_whenComponentInActiveBom_throwsLifecycleException() {
        UUID itemId = UUID.randomUUID();
        ItemEntity item = ItemEntity.builder().id(itemId).tenantId(tenantId).isActive(true).build();
        when(itemRepo.findLockedByIdAndTenantId(itemId, tenantId)).thenReturn(Optional.of(item));
        when(bomHeaderRepo.existsByTenantIdAndFinishedItemIdAndStatus(tenantId, itemId, "approved")).thenReturn(false);
        when(bomLineRepo.isComponentInActiveBom(tenantId, itemId)).thenReturn(true);

        assertThatThrownBy(() -> itemService.deleteItem(itemId))
                .isInstanceOf(LifecycleException.class)
                .hasMessageContaining("referenced as component in active BOM");
    }

    @Test
    void deactivateItem_whenComponentInActiveBom_throwsLifecycleException() {
        UUID itemId = UUID.randomUUID();
        ItemEntity item = ItemEntity.builder().id(itemId).tenantId(tenantId).isActive(true).build();
        when(itemRepo.findLockedByIdAndTenantId(itemId, tenantId)).thenReturn(Optional.of(item));
        when(bomHeaderRepo.existsByTenantIdAndFinishedItemIdAndStatus(tenantId, itemId, "approved")).thenReturn(false);
        when(bomLineRepo.isComponentInActiveBom(tenantId, itemId)).thenReturn(true);

        UpdateItemRequest updateReq = UpdateItemRequest.builder().status("inactive").build();

        assertThatThrownBy(() -> itemService.updateItem(itemId, updateReq))
                .isInstanceOf(LifecycleException.class)
                .hasMessageContaining("referenced as component in active BOM");
    }

    @Test
    void operationalDependenciesBlockDeleteAndDeactivation() {
        UUID itemId = UUID.randomUUID();
        ItemEntity item = ItemEntity.builder().id(itemId).tenantId(tenantId).isActive(true).build();
        when(itemRepo.findLockedByIdAndTenantId(itemId, tenantId)).thenReturn(Optional.of(item));
        when(operationalDependencies.findActive(tenantId, itemId)).thenReturn(List.of("production job", "dispatch"));

        assertThatThrownBy(() -> itemService.deleteItem(itemId))
                .isInstanceOf(LifecycleException.class)
                .hasMessageContaining("production job, dispatch");
        assertThatThrownBy(() -> itemService.updateItem(itemId, UpdateItemRequest.builder().status("inactive").build()))
                .isInstanceOf(LifecycleException.class)
                .hasMessageContaining("production job, dispatch");
    }

    @Test
    void categoryAndHsnChangesAreBlockedForReferencedItems() {
        UUID itemId = UUID.randomUUID();
        ItemEntity item = ItemEntity.builder().id(itemId).tenantId(tenantId).isActive(true).build();
        when(itemRepo.findLockedByIdAndTenantId(itemId, tenantId)).thenReturn(Optional.of(item));
        when(operationalDependencies.findReferences(tenantId, itemId)).thenReturn(List.of("BOM"));
        when(hsnCodeRepo.findByCode("1234"))
                .thenReturn(Optional.of(HsnCodeEntity.builder().id(UUID.randomUUID()).code("1234").build()));

        assertThatThrownBy(() -> itemService.updateItem(itemId, UpdateItemRequest.builder().categoryId(UUID.randomUUID()).build()))
                .isInstanceOf(LifecycleException.class).hasMessageContaining("BOM");
        assertThatThrownBy(() -> itemService.updateItem(itemId, UpdateItemRequest.builder().hsnCode("1234").build()))
                .isInstanceOf(LifecycleException.class).hasMessageContaining("BOM");
    }

    @Test
    void invalidStatusDoesNotDeactivateTheItem() {
        UUID itemId = UUID.randomUUID();
        ItemEntity item = ItemEntity.builder().id(itemId).tenantId(tenantId).isActive(true).build();
        when(itemRepo.findLockedByIdAndTenantId(itemId, tenantId)).thenReturn(Optional.of(item));
        assertThatThrownBy(() -> itemService.updateItem(itemId, UpdateItemRequest.builder().status("unknown").build()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Invalid item status");
    }
}
