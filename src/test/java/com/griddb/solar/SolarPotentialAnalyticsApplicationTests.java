package com.griddb.solar;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "app.seed-data.enabled=false",
        "griddb.base-url=http://localhost:0/test"
})
class SolarPotentialAnalyticsApplicationTests {

    @Test
    void contextLoads() {
    }
}
