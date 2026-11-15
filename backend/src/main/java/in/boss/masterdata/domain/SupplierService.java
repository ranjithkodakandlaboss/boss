package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.CreateSupplierRequest;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.SupplierDto;
import in.boss.masterdata.api.dto.UpdateSupplierRequest;

import java.util.UUID;

public interface SupplierService {
    SupplierDto createSupplier(CreateSupplierRequest request);
    SupplierDto getSupplier(UUID id);
    SupplierDto updateSupplier(UUID id, UpdateSupplierRequest request);
    void deleteSupplier(UUID id);
    PageResponse<SupplierDto> listSuppliers(String status, String q, int page, int size);
}
