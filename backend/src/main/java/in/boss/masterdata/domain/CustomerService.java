package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.CreateCustomerRequest;
import in.boss.masterdata.api.dto.CustomerDto;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.UpdateCustomerRequest;

import java.util.UUID;

public interface CustomerService {
    CustomerDto createCustomer(CreateCustomerRequest request);
    CustomerDto getCustomer(UUID id);
    CustomerDto updateCustomer(UUID id, UpdateCustomerRequest request);
    void deleteCustomer(UUID id);
    PageResponse<CustomerDto> listCustomers(String status, String q, int page, int size);
}
