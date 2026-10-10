package com.movinsync.tracking.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class SecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    @BeforeEach
    public void setup() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    public void testRoleBasedAccess() throws Exception {
        // 1. Admin login
        String adminToken = loginAndGetToken("admin", "admin123");

        // Admin can access /actuator/metrics
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Admin can access GET /api/vehicles/1/position
        mockMvc.perform(get("/api/vehicles/1/position").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound()); // NotFound is OK for role check

        // 2. Approver login
        String approverToken = loginAndGetToken("approver", "approver123");
        
        // Approver CAN access GET /api/vehicles/1/position
        mockMvc.perform(get("/api/vehicles/1/position").header("Authorization", "Bearer " + approverToken))
                .andExpect(status().isNotFound());

        // Approver CANNOT access /actuator/metrics
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + approverToken))
                .andExpect(status().isForbidden());

        // 3. Employee login
        String employeeToken = loginAndGetToken("employee1", "emp123");

        // Employee CAN access GET /api/pickups/me/eta
        mockMvc.perform(get("/api/pickups/me/eta").header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isNotFound());

        // Employee CANNOT access GET /api/vehicles/1/position
        mockMvc.perform(get("/api/vehicles/1/position").header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
        
        // Employee CANNOT access /actuator/metrics
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }
    
    @Test
    public void testBadCredentials() throws Exception {
        Map<String, String> creds = Map.of("username", "admin", "password", "wrongpass");
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(creds)))
                .andExpect(status().isUnauthorized());
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        Map<String, String> creds = Map.of("username", username, "password", password);
        String response = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(creds)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        
        return objectMapper.readValue(response, Map.class).get("token").toString();
    }
}
