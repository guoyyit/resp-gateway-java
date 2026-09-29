package com.github.guoyyit.respgatewayjava.tests;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

@SpringBootTest
public class ApplicationTests {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.port", () -> 0);
        registry.add("gateway.data-dir", () -> tempDataDir.toString());
    }

    @Test
    public void contextLoads() {
    }
}
