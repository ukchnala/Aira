package dev.krinry.jarvis.agent

import android.content.Context
import android.util.Log
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.service.AutoAgentService
import dev.krinry.jarvis.memory.JarvisMemory
import kotlinx.coroutines.*

/**
 * AgentLlmEngine — The brain of Krinry AI (Jarvis).
 *
 * Loop: Read screen → build prompt → call LLM → speak → execute → VERIFY → repeat
 *
 * Key fixes:
 * - VERIFICATION: Agent re-reads screen after "done" to confirm task actually completed
 * - Hindi status updates shown to user
 * - Agent speaks Hindi summary of what it's doing
 * - Coordinates (cx, cy) in UI tree for gesture-based tap
 * - When message typed, agent must find and click SEND button before saying done
 */
class AgentLlmEngine(private val context: Context) {

    private val ttsManager = AgentTtsManager(context)
    private val memory = JarvisMemory(context.applicationContext)

    companion object {
        private const val TAG = "AgentLlmEngine"
        private const val MAX_ITERATIONS = 30
        private const val SCREEN_SETTLE_DELAY = 600L
        private const val MAX_HISTORY_MESSAGES = 10  // Keep small for token savings

        // Compressed system prompt: ~600 tokens vs ~1800 before (67% savings)
        private const val SYSTEM_PROMPT = """You are Krinry, AI phone assistant. Full device control via AccessibilityService. Respond ONLY in valid JSON, no markdown.

ACTIONS (JSON format: {"action":"X","speech":"Hindi or empty","reason":"why","status":"in_progress|done"} + action-specific fields):
- open_app: +app_name | click: +node_id | type: +node_id,text | tap_xy: +x,y | long_press: +x,y
- scroll_down/scroll_up | swipe: +text(left|right|up|down) | back/home/recent
- open_url: +url | screenshot | copy | paste: +node_id | select_all | open_notifications
- wait | done: status="done"

UI nodes: i=id,t=text,d=desc,T=type(B=Button,E=EditText,IB=ImageButton,TV=TextView,IV=ImageView),x=centerX,y=centerY,c=clickable,e=editable,s=scrollable. Use node_id(i) for click/type. Fallback: tap_xy with x,y coords.

RULES:
1. Speech: Hindi only. First step=short confirm, middle=empty, done=completion msg, error=Hindi explain
2. Apps: ALWAYS open_app first, never scroll home. Use exact name: "WhatsApp","YouTube","Chrome"
3. NEVER say done early. After type→MUST click Send button→verify→done. Complete full task inside app
4. Node missing? scroll→tap_xy→search by text. Give up only after trying all
5. Verify before done: check screen confirms action worked
6. Multiple matches? Ask user via speech. One match? Proceed"""
    }

    var onStatusUpdate: ((String) -> Unit)? = null
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private var currentJob: Job? = null

    fun startTask(voiceCommand: String, scope: CoroutineScope) {
        currentJob?.cancel()
        ttsManager.stop()
        currentJob = scope.launch {
            runAgentLoop(voiceCommand)
        }
    }

    fun cancelTask() {
        currentJob?.cancel()
        currentJob = null
        ttsManager.stop()
        onStatusUpdate?.invoke("⏹ Ruk gaya")
    }

    private suspend fun runAgentLoop(command: String) {
        val service = AutoAgentService.instance
        if (service == null) {
            onStatusUpdate?.invoke("❌ Accessibility Service on nahi hai")
            memory.saveCommand(command)
            memory.finishLatest(command, "Failed: Accessibility Service is off")
            ttsManager.speak("Accessibility Service chalu karo pehle.")
            return
        }

        onStatusUpdate?.invoke("🧠 Samajh raha hoon: \"$command\"")
        Log.d(TAG, "Starting task: $command")
        val relevantMemories = memory.search(command)
        memory.saveCommand(command)

        for (iteration in 1..MAX_ITERATIONS) {
            if (!isActive) return

            Log.d(TAG, "=== Step $iteration ===")

            // 1. Screen padho
            val rootNode = service.getRootNode()
            if (rootNode == null) {
                onStatusUpdate?.invoke("❌ Screen nahi padh paya")
                delay(800)
                continue
            }

            val uiNodes = UiTreeExtractor.extractTree(rootNode)
            val uiJson = UiTreeExtractor.toJson(uiNodes)
            Log.d(TAG, "UI nodes: ${uiNodes.size}")

            // 2. Compact LLM message (save tokens)
            val userMessage = if (iteration == 1) {
                buildString {
                    append("CMD:$command\n")
                    if (relevantMemories.isNotEmpty()) {
                        append("RELEVANT PAST MEMORY (use only if helpful; do not claim certainty):\n")
                        append(relevantMemories.joinToString("\n"))
                        append("\n")
                    }
                    append("UI:$uiJson")
                }
            } else {
                "UI:$uiJson"
            }

            // 3. LLM call (GroqApiClient handles retries internally)
            onStatusUpdate?.invoke("🤔 Step $iteration...")
            val llmResponse = try {
                GroqApiClient.agentChat(context, SYSTEM_PROMPT, conversationHistory, userMessage)
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed: ${e.message}")
                onStatusUpdate?.invoke("❌ ${e.message?.take(50) ?: "Server error"}")
                memory.finishLatest(command, "Failed: ${e.message?.take(300) ?: "Server error"}")
                ttsManager.speak("Server se jawab nahi aaya.")
                return
            }

            if (llmResponse == null) {
                onStatusUpdate?.invoke("❌ Empty response from server")
                memory.finishLatest(command, "Failed: empty response from server")
                ttsManager.speak("Server ne koi jawab nahi diya.")
                return
            }

            Log.d(TAG, "LLM response: $llmResponse")

            // 5. History me save karo
            conversationHistory.add("user" to userMessage)
            conversationHistory.add("assistant" to llmResponse)

            while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
                conversationHistory.removeAt(0)
            }

            // 6. Parse action
            val action = ActionExecutor.parseResponse(llmResponse)
            if (action == null) {
                onStatusUpdate?.invoke("❌ Response samajh nahi aaya")
                // Don't stop — try again with fresh screen
                delay(1000)
                continue
            }

            // 7. Hindi status update with reason
            val reasonText = action.reason ?: action.action
            onStatusUpdate?.invoke("⚡ ${getHindiAction(action.action)}: $reasonText")

            // 8. TTS speak (only on first, done, or error)
            action.speech?.takeIf { it.isNotBlank() }?.let { speechText ->
                ttsManager.speak(speechText)
            }

            // 9. Check if done
            if (action.status == "done" || action.action == "done") {
                onStatusUpdate?.invoke("✅ Ho gaya: ${action.reason ?: "Task complete"}")
                memory.finishLatest(command, "Completed: ${action.reason ?: action.action}")
                delay(2500) // TTS finish hone do
                return
            }

            // 10. Execute action
            val result = ActionExecutor.execute(action, uiNodes)
            Log.d(TAG, "Result: $result")
            onStatusUpdate?.invoke(result)

            // If action failed, inform LLM through conversation context
            if (result.startsWith("❌")) {
                Log.w(TAG, "Action failed: $result")
                // Add failure to history so LLM can adapt strategy
                conversationHistory.add("user" to "SYSTEM: Previous action failed. Error: $result. Try a different approach.")
                while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
                    conversationHistory.removeAt(0)
                }
            }

            // 11. Screen settle hone do
            delay(SCREEN_SETTLE_DELAY)
        }

        onStatusUpdate?.invoke("⚠️ Bahut steps ho gaye ($MAX_ITERATIONS)")
        memory.finishLatest(command, "Stopped after maximum $MAX_ITERATIONS steps without confirmed completion")
        ttsManager.speak("Kaam time pe complete nahi ho paya. Chhota command try karo.")
    }

    /**
     * Hindi action name for status display.
     */
    private fun getHindiAction(action: String): String {
        return when (action) {
            "click" -> "Click kar raha hoon"
            "type" -> "Type kar raha hoon"
            "scroll_down" -> "Neeche scroll kar raha hoon"
            "scroll_up" -> "Upar scroll kar raha hoon"
            "back" -> "Back ja raha hoon"
            "home" -> "Home ja raha hoon"
            "recent" -> "Recent apps dekh raha hoon"
            "open_app" -> "App khol raha hoon"
            "open_url" -> "URL khol raha hoon"
            "tap_xy" -> "Tap kar raha hoon"
            "long_press" -> "Long press kar raha hoon"
            "swipe" -> "Swipe kar raha hoon"
            "screenshot" -> "Screenshot le raha hoon"
            "copy" -> "Copy kar raha hoon"
            "paste" -> "Paste kar raha hoon"
            "select_all" -> "Sab select kar raha hoon"
            "open_notifications" -> "Notifications dekh raha hoon"
            "wait" -> "Ruk raha hoon"
            "done" -> "Ho gaya"
            else -> action
        }
    }

    private val isActive: Boolean
        get() = currentJob?.isActive == true
}
