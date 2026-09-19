package com.forgerig.gatekeeper.prompts

// One wire format for every backend. System prompts name a USER_TEXT block,
// so the user content is sent under exactly that label — previously ORT sent
// it bare while LiteRT used bespoke <<<USER>>>/<<<END>>> markers, and neither
// matched the prompt wording. Framing lives here (not in each client) so the
// format cannot diverge per backend again.
object PromptFraming {
    const val USER_LABEL = "USER_TEXT"

    fun wrap(systemPrompt: String, userContent: String): String =
        if (systemPrompt.isBlank()) "$USER_LABEL:\n$userContent"
        else "$systemPrompt\n\n$USER_LABEL:\n$userContent"
}
