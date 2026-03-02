# wSocket Kotlin SDK

Kotlin/JVM SDK for [wSocket](https://wsocket.io) — Realtime Pub/Sub with Presence, History, and Push Notifications.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.wsocket:wsocket-io:0.1.0")
}
```

### Gradle (Groovy)

```groovy
implementation 'io.wsocket:wsocket-io:0.1.0'
```

### Maven

```xml
<dependency>
    <groupId>io.wsocket</groupId>
    <artifactId>wsocket-io</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Quick Start

```kotlin
import io.wsocket.sdk.WSocket
import io.wsocket.sdk.createClient

fun main() {
    val client = createClient("ws://localhost:9001", "your-api-key")
    client.connect()

    val chat = client.pubsub.channel("chat:general")

    chat.subscribe { data, meta ->
        println("[${meta.channel}] $data")
    }

    chat.publish(mapOf("text" to "Hello from Kotlin!"))

    // Presence
    chat.presence.enter(mapOf("name" to "Alice"))
    chat.presence.onEnter { member -> println("Joined: ${member.clientId}") }

    // History
    chat.onHistory { result -> println("${result.messages.size} messages") }
    chat.history(limit = 50)

    Thread.sleep(5000)
    client.disconnect()
}
```

## Push Notifications

```kotlin
val push = client.configurePush("https://api.wsocket.io", "admin-token", "app-id")
push.registerFCM("device-token", "user-123")
push.sendToMember("user-123", mapOf("title" to "Hello", "body" to "World"))
push.broadcast(mapOf("title" to "Announcement"))
```

## License

MIT
