package com.cognizant.emk.multiagent.infrastructure.tool;

import com.cognizant.emk.multiagent.domain.tool.ToolGroup;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-only catalog entry, on the test classpath only. {@code @Profile("dev")} gates it
 * out of production. Exists so the catalog adapter integration test has a known second
 * entry alongside the production {@code AwsS3Tool} (US-07-003) and so the test asserts
 * the discovery mechanism works for any class, not just the bundled S3 tool.
 */
@Component
@Profile("dev")
@ToolGroup(name = "TestTool", description = "Used by the unit test only.")
public class TestToolFixture {
}
