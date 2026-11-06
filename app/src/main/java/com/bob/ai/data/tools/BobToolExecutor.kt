package com.bob.ai.data.tools

import com.bob.ai.domain.ToolExecutionResult
import com.bob.ai.domain.ToolExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Dispatches a tool call (by fixed UID) to the executor that handles it:
 * file tools -> [FileToolsExecutor], text tools -> [TextToolsExecutor],
 * daily tools -> [DailyToolsExecutor]. Unknown UIDs fail with a clear error.
 */
class BobToolExecutor(
    private val fileTools: FileToolsExecutor,
    private val textTools: TextToolsExecutor,
    private val dailyTools: DailyToolsExecutor
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(uid: String, argumentsJson: String): ToolExecutionResult {
        val args: JsonObject = when {
            argumentsJson.isBlank() -> JsonObject(emptyMap())
            else -> runCatching {
                json.parseToJsonElement(argumentsJson).jsonObject
            }.getOrElse {
                return ToolExecutionResult(
                    false, "",
                    "Tham số của công cụ không hợp lệ: ${it.message}"
                )
            }
        }
        return when {
            fileTools.handles(uid) -> fileTools.execute(uid, args)
            textTools.handles(uid) -> textTools.execute(uid, args)
            dailyTools.handles(uid) -> dailyTools.execute(uid, args)
            else -> ToolExecutionResult(false, "", "Không có công cụ với UID: $uid")
        }
    }
}
