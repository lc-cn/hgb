package io.github.yysassist.core.content

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal

class BundleCodecTest {
    private val codec = BundleCodec(listOf(CapabilityContract("observe", 1) { inputs ->
        if (inputs.isEmpty()) null else "observe不接受额外参数"
    }))
    private fun valid() = TaskBundle(1, "release-a", "2026-11-16T00:00:00Z", "说明",
        listOf(TaskDefinition("claim", "onmyoji", "领取", "daily", 10, steps = listOf(
            StepDefinition("observe", "观察", listOf(ActionDefinition("screen", "observe", 1, JsonObject(emptyMap())))),
        ))), targets = listOf(TargetProfile("onmyoji", "阴阳师测试定义", listOf("test.onmyoji"),
            IdentityPolicy.REQUIRED, listOf(TaskCategory("daily", "测试领取")))))

    @Test fun `task contents round trip and catalog is external data`() {
        val original = valid()
        assertEquals(BundleReadResult.Valid(original), codec.read(codec.write(original)))
        val changed = original.copy(version = "release-b", tasks = original.tasks.map { it.copy(name = "云端更新名称") })
        assertEquals(BundleReadResult.Valid(changed), codec.read(codec.write(changed)))
    }
    @Test fun `unknown capability version field or malformed content never loads partially`() {
        val text = codec.write(valid())
        assertTrue(codec.read(text.replace("observe\"", "shell\"")) is BundleReadResult.Invalid)
        assertTrue(codec.read(text.replace("\"contractVersion\":1", "\"contractVersion\":2")) is BundleReadResult.Invalid)
        assertTrue(codec.read(text.dropLast(1)) is BundleReadResult.Invalid)
        assertTrue(codec.read(text.dropLast(1) + ",\"execute\":\"remote-code\"}") is BundleReadResult.Invalid)
    }
    @Test fun `range requires bounds and does not invent defaults`() {
        val task = valid().tasks.single()
        val missing = valid().copy(tasks = listOf(task.copy(fields = listOf(ConfigurationField("count", "数量", FieldWidget.RANGE)))))
        assertTrue(codec.validate(missing).any { it.reason.contains("明确范围") })
        val correct = missing.copy(tasks = listOf(task.copy(fields = listOf(ConfigurationField("count", "数量", FieldWidget.RANGE,
            minimum = JsonPrimitive(1), maximum = JsonPrimitive(10), step = JsonPrimitive(1))))))
        assertTrue(codec.validate(correct).isEmpty())
        assertFalse(codec.write(correct).contains("default"))
    }
    @Test fun `duplicate task step and field identities are rejected`() {
        val task = valid().tasks.single()
        assertTrue(codec.validate(valid().copy(tasks = listOf(task, task))).any { it.reason == "任务身份重复" })
        assertTrue(codec.validate(valid().copy(tasks = listOf(task.copy(steps = task.steps + task.steps)))).any { it.reason.contains("身份重复") })
    }
    @Test fun `action arguments are checked by registered apk contract`() {
        val task = valid().tasks.single()
        val step = task.steps.single()
        val action = step.actions.single().copy(inputs = JsonObject(mapOf("command" to JsonPrimitive("anything"))))
        val bundle = valid().copy(tasks = listOf(task.copy(steps = listOf(step.copy(actions = listOf(action))))))
        assertTrue(codec.validate(bundle).any { it.reason.contains("额外参数") })
    }

    @Test fun `target definitions own categories and task identity namespaces`() {
        val original = valid()
        val other = original.targets.single().copy(id = "utility", name = "工具应用", androidPackages = listOf("test.utility"),
            identityPolicy = IdentityPolicy.NONE, categories = listOf(TaskCategory("daily", "整理")))
        val bundle = original.copy(targets = original.targets + other,
            tasks = original.tasks + original.tasks.single().copy(targetId = "utility"))
        assertEquals(BundleReadResult.Valid(bundle), codec.read(codec.write(bundle)))
        assertTrue(codec.validate(original.copy(tasks = original.tasks.map { it.copy(targetId = "undeclared") }))
            .any { it.reason == "目标应用未声明" })
        assertTrue(codec.validate(original.copy(tasks = original.tasks.map { it.copy(category = "undefined") }))
            .any { it.reason == "分类不属于目标应用" })
    }

    @Test fun `unknown identity anonymous identity and required account are distinct`() {
        val required = valid().targets.single()
        val anonymous = ExecutionContext(required.id, Principal.Anonymous)
        val signedIn = ExecutionContext(required.id, Principal.Account("opaque-account"))
        assertFalse(required.acceptsContext(null))
        assertFalse(required.acceptsContext(anonymous))
        assertTrue(required.acceptsContext(signedIn))
        assertTrue(required.copy(identityPolicy = IdentityPolicy.NONE).acceptsContext(anonymous))
        assertTrue(required.copy(identityPolicy = IdentityPolicy.OPTIONAL).acceptsContext(anonymous))
        assertFalse(required.acceptsContext(signedIn.copy(targetId = "another-app")))
    }

    @Test fun `resources are versioned data with exact identities lengths and no paths or URLs`() {
        val resource = TaskResource("ocr.det", "det.onnx", 10, "a".repeat(64))
        val bundle = valid().copy(resources = listOf(resource))
        assertEquals(BundleReadResult.Valid(bundle), codec.read(codec.write(bundle)))
        for (bad in listOf(resource.copy(id = ""), resource.copy(bytes = 0), resource.copy(bytes = -1),
            resource.copy(sha256 = "SHA1"), resource.copy(assetName = "task-bundle.json"),
            resource.copy(assetName = "../det.onnx"), resource.copy(assetName = "https://other/model"),
            resource.copy(assetName = "/sdcard/file"))) {
            assertTrue(bad.toString(), codec.validate(bundle.copy(resources = listOf(bad))).isNotEmpty())
        }
        assertTrue(codec.validate(bundle.copy(resources = listOf(resource, resource.copy(assetName = "other.onnx")))).isNotEmpty())
        assertTrue(codec.validate(bundle.copy(resources = listOf(resource, resource.copy(id = "alias", bytes = 11, assetName = "other.onnx")))).isNotEmpty())
        assertTrue(codec.validate(bundle.copy(resources = listOf(resource, resource.copy(id = "other")))).isNotEmpty())
        // Aliases may share identical content, including across targets, without a second physical blob.
        assertTrue(codec.validate(bundle.copy(resources = listOf(resource, resource.copy(id = "alias", assetName = "alias.onnx")))).isEmpty())
    }
}
