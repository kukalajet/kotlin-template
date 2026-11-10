@file:OptIn(ExperimentalKtorApi::class)

package kotlintemplate.api

import io.ktor.http.ContentType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Responses
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.hide
import io.ktor.utils.io.ExperimentalKtorApi

private val baseDocument =
    OpenApiDoc.build {
        info = OpenApiInfo(title = "kotlin-template API", version = "1.0.0")
    }

/**
 * `GET /openapi.json`: the OpenAPI 3.1 document generated from the routes'
 * `describe` blocks, built on the first request, once every route exists.
 */
fun Route.openApiRoute() {
    val document by lazy {
        OpenApiDocSource.Routing(contentType = ContentType.Application.Json).read(application, baseDocument).content
    }

    get("/openapi.json") {
        call.respondText(document, ContentType.Application.Json)
    }.hide()
}

/** The error responses an operation can answer with, each described by its code. */
internal fun Responses.Builder.errorResponse(vararg errors: ApiError) {
    errors.groupBy { it.status }.forEach { (status, sameStatus) ->
        status {
            description = sameStatus.joinToString(" or ") { it.code }
            schema = jsonSchema<ErrorBody>()
        }
    }
}
