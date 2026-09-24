package dev.inspector.daemon

import dev.inspector.daemon.mcp.McpServer
import dev.inspector.daemon.mcp.McpTools
import dev.inspector.daemon.usb.UsbBridge
import kotlin.io.path.Path
import kotlin.system.exitProcess

private const val USAGE = """
inspector — host daemon for the Compose Multiplatform network inspector

Usage:
  inspector serve [--port N] [--data DIR] [--max-sessions N] [--max-mb N] [--no-usb] [--usb-port N]
  inspector mcp   [--data DIR] [--port N]
  inspector sessions
  inspector summary  [--session ID]
  inspector query    '<filter>' [--session ID] [--limit N] [--offset N]
  inspector body     <txnId> --side req|res [--session ID]
  inspector prune

--session accepts the literal 'latest' (the default).

serve also bridges a USB-attached iPhone when usbmuxd is present (every Mac): the app
listens on the phone's own 127.0.0.1:<port> and the daemon dials it over the cable.
--no-usb turns that off. --usb-port dials a port other than the daemon's own — for a
second daemon beside your usual one, while the app still listens on the default.

Filter grammar:
  status:404  status>=400        method:POST       host:api.example.com
  path:/v2/users                 slower:500ms      larger:10kb
  has:error   text:refund        attempt>1         since:marker("label")
  Space-separated terms are ANDed; '|' ORs and binds more loosely.
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in listOf("-h", "--help", "help")) {
        println(USAGE.trim())
        return
    }

    val flags = parseFlags(args.drop(1))
    val config = DaemonConfig.load(
        dataDir = flags["data"]?.let { Path(it) } ?: DaemonConfig.defaultDataDir(),
        port = flags["port"]?.toIntOrNull(),
        maxSessions = flags["max-sessions"]?.toIntOrNull(),
        maxMb = flags["max-mb"]?.toLongOrNull(),
    )
    val repository = SessionRepository(config)

    when (args[0]) {
        "serve" -> try {
            InspectorDaemon(config).start(wait = true) {
                if (flags["no-usb"] == null) {
                    UsbBridge.startIfAvailable(
                        daemonPort = config.port,
                        devicePort = flags["usb-port"]?.toIntOrNull() ?: config.port,
                    )
                }
            }
        } catch (e: PortUnavailableException) {
            fail(
                "port ${e.port} is already in use — another daemon is probably still running.\n" +
                    "  find it:  lsof -nP -iTCP:${e.port} -sTCP:LISTEN\n" +
                    "  or pick another port:  inspector serve --port ${e.port + 1}"
            )
        }

        "mcp" -> {
            // stdout is the protocol channel from here on; diagnostics go to stderr only.
            McpServer(
                tools = McpTools(config),
                input = System.`in`.bufferedReader(),
                output = System.out,
                log = System.err,
            ).run()
        }

        "sessions" -> {
            val sessions = repository.listSessions()
            if (sessions.isEmpty()) {
                println("no sessions in ${config.sessionsDir}")
            } else {
                sessions.forEach {
                    println("${it.sessionId}  ${it.txnCount} txns, ${it.errorCount} errors  (${it.device})")
                }
            }
        }

        "summary" -> {
            val dir = repository.resolve(flags["session"] ?: "latest") ?: fail("no such session")
            val summary = repository.summarize(dir) ?: fail("session has no metadata")
            println(dev.inspector.model.InspectorJsonPretty.encodeToString(SessionSummary.serializer(), summary))
        }

        "query" -> {
            val filter = args.getOrNull(1)?.takeUnless { it.startsWith("--") }.orEmpty()
            val dir = repository.resolve(flags["session"] ?: "latest") ?: fail("no such session")
            val page = try {
                repository.queryTransactions(
                    dir,
                    filter,
                    flags["offset"]?.toIntOrNull() ?: 0,
                    flags["limit"]?.toIntOrNull() ?: 50,
                )
            } catch (e: dev.inspector.model.FilterParseException) {
                fail(e.message ?: "invalid filter")
            }
            println(dev.inspector.model.InspectorJsonPretty.encodeToString(TransactionPage.serializer(), page))
        }

        "body" -> {
            val txnId = args.getOrNull(1) ?: fail("usage: inspector body <txnId> --side req|res")
            val side = flags["side"] ?: fail("--side must be 'req' or 'res'")
            val dir = repository.resolve(flags["session"] ?: "latest") ?: fail("no such session")
            val bytes = repository.readBody(dir, txnId, side) ?: fail("no $side body for $txnId")
            System.out.write(bytes)
            System.out.flush()
        }

        "prune" -> {
            val result = Retention(config, repository).prune()
            println(
                "pruned ${result.prunedSessionIds.size} session(s); " +
                    "${result.remainingSessions} remain, ${result.remainingBytes / 1024 / 1024} MB"
            )
        }

        else -> fail("unknown command '${args[0]}'. Run 'inspector --help'.")
    }
}

private fun parseFlags(args: List<String>): Map<String, String> {
    val flags = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--")) {
            val key = arg.removePrefix("--")
            val value = args.getOrNull(i + 1)
            if (value != null && !value.startsWith("--")) {
                flags[key] = value
                i += 2
                continue
            }
            flags[key] = "true"
        }
        i++
    }
    return flags
}

private fun fail(message: String): Nothing {
    System.err.println("inspector: $message")
    exitProcess(1)
}
