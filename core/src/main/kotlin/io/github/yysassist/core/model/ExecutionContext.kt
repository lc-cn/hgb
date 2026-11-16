package io.github.yysassist.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An explicit anonymous identity is different from a not-yet-verified context (null). */
@Serializable
sealed interface Principal {
    @Serializable @SerialName("anonymous") data object Anonymous : Principal
    @Serializable @SerialName("account") data class Account(val id: String) : Principal
}

@Serializable
data class ExecutionContext(val targetId: String, val principal: Principal) {
    fun isValid(): Boolean = targetId.isNotBlank() && when (principal) {
        Principal.Anonymous -> true
        is Principal.Account -> principal.id.isNotBlank()
    }
}
