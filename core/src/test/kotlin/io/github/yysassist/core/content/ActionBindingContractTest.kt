package io.github.yysassist.core.content

import io.github.yysassist.core.execution.BuiltInStepWorkFactory
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ActionBindingContractTest {
    private val contract = BuiltInStepWorkFactory().contracts.single()
    private val codec = BundleCodec(listOf(contract))
    private val field = ConfigurationField("milliseconds", "等待时长", FieldWidget.NUMBER, required = true)
    private val first = ActionDefinition("first", "control.wait", 1, JsonObject(emptyMap()),
        mapOf("durationMs" to ActionInputBinding.Configuration("milliseconds")))
    private val second = first.copy(id = "second", bindings = mapOf("durationMs" to ActionInputBinding.Output("first", "durationMs")))
    private fun bundle(actions: List<ActionDefinition> = listOf(first, second), fields: List<ConfigurationField> = listOf(field)) =
        TaskBundle(1, "bindings", "2026-11-16T00:00:00Z", "", listOf(TaskDefinition("wait", "utility", "等待", "tools", 1,
            fields, listOf(StepDefinition("step", "阶段", actions)))), listOf(TargetProfile("utility", "工具", listOf("test.utility"),
            IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))))

    @Test fun structuredReferencesRoundTripAndOldLiteralDefinitionsKeepTheirWireShape() {
        val original = bundle()
        val text = codec.write(original)
        assertTrue(text.contains("\"type\":\"configuration\""))
        assertTrue(text.contains("\"type\":\"output\""))
        assertEquals(BundleReadResult.Valid(original), codec.read(text))
        val old = bundle(listOf(first.copy(inputs = buildJsonObject { put("durationMs", 0) }, bindings = emptyMap())), emptyList())
        assertFalse(codec.write(old).contains("\"bindings\":"))
        assertEquals(BundleReadResult.Valid(old), codec.read(codec.write(old)))
        assertTrue(codec.read(text.replace("\"type\":\"output\"", "\"type\":\"script\"")) is BundleReadResult.Invalid)
    }

    @Test fun missingOptionalOrWrongTypeConfigurationCannotBecomeAnImplicitDefault() {
        for (fields in listOf(emptyList(), listOf(field.copy(required = false)), listOf(field.copy(widget = FieldWidget.TEXT)))) {
            assertTrue(codec.validate(bundle(fields = fields)).isNotEmpty())
        }
        assertTrue(codec.validate(bundle(listOf(first.copy(inputs = buildJsonObject { put("durationMs", 0) })))).isNotEmpty())
        assertTrue(codec.validate(bundle(listOf(first.copy(bindings = mapOf("unknown" to ActionInputBinding.Configuration(field.id)))))).isNotEmpty())
        val literal = first.copy(inputs = buildJsonObject { put("durationMs", "configuration.milliseconds") }, bindings = emptyMap())
        assertTrue(codec.validate(bundle(listOf(literal))).isNotEmpty()) // Never evaluate strings as code.
    }

    @Test fun referencesCannotReadSelfFutureOtherStepOrUndeclaredOutputs() {
        for (actions in listOf(listOf(second, first), listOf(first.copy(bindings = mapOf("durationMs" to ActionInputBinding.Output("first", "durationMs")))),
            listOf(first, second.copy(bindings = mapOf("durationMs" to ActionInputBinding.Output("first", "missing")))))) {
            assertTrue(codec.validate(bundle(actions)).isNotEmpty())
        }
        val original = bundle()
        val task = original.tasks.single()
        val split = original.copy(tasks = listOf(task.copy(steps = listOf(StepDefinition("one", "一", listOf(first)),
            StepDefinition("two", "二", listOf(second))))))
        assertTrue(codec.validate(split).isNotEmpty())
    }

    @Test fun actualConfigurationIsCheckedByTheSameActionContractBeforeAdmission() {
        val task = bundle().tasks.single()
        assertTrue(codec.configurationIssues(task, buildJsonObject { put(field.id, 7) }).isEmpty())
        for (value in listOf(JsonPrimitive(-1), JsonPrimitive(0.5), JsonPrimitive("7"), JsonPrimitive("1e100"), JsonNull)) {
            val issues = codec.configurationIssues(task, JsonObject(mapOf(field.id to value)))
            assertTrue(value.toString(), issues.isNotEmpty())
            assertEquals(field.id, issues.first().location)
        }
        assertTrue(codec.configurationIssues(task, JsonObject(emptyMap())).isNotEmpty())
        // Schema acceptance alone does not mean that the action can consume that numeric value.
        assertTrue(validateConfiguration(task, buildJsonObject { put(field.id, -1) }).isEmpty())
    }

    @Test fun exactIntegralNumbersKeepTheirMeaningAcrossConfigurationAndActionContracts() {
        val task = bundle().tasks.single()
        for (representation in listOf("12", "12.00", "1.2e1", "9223372036854775807")) {
            val number = Json.parseToJsonElement(representation)
            val values = JsonObject(mapOf(field.id to number))
            assertTrue(representation, validateConfiguration(task, values).isEmpty())
            assertTrue(representation, codec.configurationIssues(task, values).isEmpty())
        }
        for (representation in listOf("12.01", "-1", "9223372036854775808", "1e100")) {
            val values = JsonObject(mapOf(field.id to Json.parseToJsonElement(representation)))
            assertFalse(representation, codec.configurationIssues(task, values).isEmpty())
        }
    }

    @Test fun outputProvenanceAndValueTypesRemainExplicitAtResolution() {
        val configuration = buildJsonObject { put(field.id, 15) }
        assertEquals(buildJsonObject { put("durationMs", 15) }, resolveActionInputs(first, configuration, emptyMap()))
        val outputs = mapOf("first" to buildJsonObject { put("durationMs", 6) })
        assertEquals(buildJsonObject { put("durationMs", 6) }, resolveActionInputs(second, configuration, outputs))
        assertThrows(IllegalArgumentException::class.java) { resolveActionInputs(second, configuration, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { resolveActionInputs(first, JsonObject(emptyMap()), outputs) }
        assertNull(contract.outputIssue(outputs.getValue("first")))
        assertNotNull(contract.outputIssue(buildJsonObject { put("durationMs", "6") }))
        assertNotNull(contract.outputIssue(JsonObject(emptyMap())))
        assertNotNull(contract.outputIssue(buildJsonObject { put("durationMs", 6); put("unexpected", true) }))
    }
}
