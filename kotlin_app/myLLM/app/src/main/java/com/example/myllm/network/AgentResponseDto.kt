package com.example.myllm.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class AgentResponseDto(
    val type: String,
    val message: String? = null,
    val action: String? = null,
    val args: Map<String, JsonElement>? = null
)

/**
 * /chat/step
 * {
 *    'type': 'ACTION',
 *    'action': 'type_text_at',
 *    'args': {'text': '내일 날씨', 'x': 450, 'y': 240, 'press_enter': True}
 *  } - 일반 조작 명령
 * {
 *    'type': 'RESPONSE',
 *    'message': '\n스크롤하니 요일별 날씨가 보입니다. ~~'
 *  } - 최종 응답
 * {
 *    'type': 'ERROR',
 *    'message': "Model generation error: 400 ~~"
 * } - 에러
 * */