package com.mywork.billingservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mywork.billingservice.dto.CustomerRequest;
import com.mywork.billingservice.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests - tests the full stack with a real Postgres database.
 * No mocks - every layer is exercised: controller -> service -> repository -> DB.
 * Testcontainers spins up a real Postgres container for each test run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CustomerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("billingdb")
            .withUsername("billing")
            .withPassword("billing123");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customerRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void cleanDatabase() {
        customerRepository.deleteAll();
    }

    @Test
    @DisplayName("Should create customer and persist to database")
    void createCustomerShouldPersistToDatabase() throws Exception {
        CustomerRequest request = new CustomerRequest("John Doe", "john@example.com", "07700900000");

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("John Doe"))
                .andExpect(jsonPath("$.email").value("john@example.com"));

        assertThat(customerRepository.count()).isEqualTo(1);
        assertThat(customerRepository.findByEmail("john@example.com")).isPresent();
    }

    @Test
    @DisplayName("Should return 409 when creating customer with duplicate email")
    void createCustomerShouldReturn409WhenEmailExists() throws Exception {
        CustomerRequest request = new CustomerRequest("John Doe", "john@example.com", "07700900000");

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(customerRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should retrieve customer by id from database")
    void getCustomerByIdShouldReturnFromDatabase() throws Exception {
        CustomerRequest request = new CustomerRequest("Jane Doe", "jane@example.com", null);
        MvcResult createResult = mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        Long id = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(get("/api/customers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value("jane@example.com"));
    }

    @Test
    @DisplayName("Should return 404 when customer not found")
    void getCustomerByIdShouldReturn404WhenNotFound() throws Exception {
        mockMvc.perform(get("/api/customers/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("Should return all customers from database")
    void getAllCustomersShouldReturnAllFromDatabase() throws Exception {
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CustomerRequest("John", "john@example.com", null))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CustomerRequest("Jane", "jane@example.com", null))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("Should update customer in database")
    void updateCustomerShouldPersistChanges() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CustomerRequest("John Doe", "john@example.com", "07700900000"))))
                .andExpect(status().isCreated())
                .andReturn();

        Long id = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(put("/api/customers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CustomerRequest("John Updated", "john@example.com", "07700900001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("John Updated"));

        assertThat(customerRepository.findById(id))
                .isPresent()
                .get()
                .extracting("name")
                .isEqualTo("John Updated");
    }

    @Test
    @DisplayName("Should delete customer from database")
    void deleteCustomerShouldRemoveFromDatabase() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CustomerRequest("John Doe", "john@example.com", null))))
                .andExpect(status().isCreated())
                .andReturn();

        Long id = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(delete("/api/customers/{id}", id))
                .andExpect(status().isNoContent());

        assertThat(customerRepository.findById(id)).isEmpty();
        assertThat(customerRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should return 400 when creating customer with invalid data")
    void createCustomerShouldReturn400WhenInvalidData() throws Exception {
        CustomerRequest invalidRequest = new CustomerRequest("", "not-an-email", null);

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists());

        assertThat(customerRepository.count()).isEqualTo(0);
    }
}
