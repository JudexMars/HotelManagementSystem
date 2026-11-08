package dev.hotelbooking.booking.identity.api

import dev.hotelbooking.booking.identity.domain.Credentials
import dev.hotelbooking.booking.identity.domain.Role
import dev.hotelbooking.booking.identity.domain.UserProfile
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

data class CreateUserRequest(
    val username: String,
    val password: String,
    val role: Role,
) {
    override fun toString() = "CreateUserRequest(username=$username, password=<redacted>, role=$role)"
}

data class UpdateUserRequest(
    val username: String,
    val role: Role,
    val password: String? = null,
) {
    override fun toString() = "UpdateUserRequest(username=$username, role=$role, password=<redacted>)"
}

data class UserPageResponse(
    val items: List<UserResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Long,
)

@RestController
@RequestMapping("/api/users")
class UserAdministrationController(
    private val service: AuthorizedUserAdministrationService,
) {
    @PostMapping
    @Operation(summary = "Создать пользователя (ADMIN)")
    @ApiResponse(responseCode = "201", description = "Пользователь создан", useReturnTypeSchema = true)
    fun create(
        @RequestBody request: CreateUserRequest,
    ): ResponseEntity<UserResponse> {
        val user = service.create(Credentials(request.username, request.password), request.role)
        return ResponseEntity.created(URI.create("/api/users/${user.id}")).body(user.toResponse())
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): UserResponse = service.get(id).toResponse()

    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): UserPageResponse {
        val result = service.list(page, size)
        return UserPageResponse(result.users.map(UserProfile::toResponse), page, size, result.total, (result.total + size - 1) / size)
    }

    @PutMapping("/{id}")
    @Operation(summary = "Обновить пользователя; отсутствующий/null пароль сохраняет прежний")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: UpdateUserRequest,
    ): UserResponse = service.update(id, request.username, request.role, request.password).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Логически удалить пользователя без отзыва прежних JWT")
    fun delete(
        @PathVariable id: UUID,
    ) = service.delete(id)
}

private fun UserProfile.toResponse() = UserResponse(id, username, role)
