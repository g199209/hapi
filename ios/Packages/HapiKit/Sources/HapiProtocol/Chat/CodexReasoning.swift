import Foundation

// Protocol parity with web/src/chat/codexReasoning.ts. Only shared bridge IDs
// identify a thread and turn; other providers keep their original blocks.
private func reasoningScope(_ id: String) -> String? {
    let parts = id.split(separator: ":", omittingEmptySubsequences: false)
    guard parts.count == 5, parts[0] == "codex", parts[4] == "agent_reasoning",
          parts[1...3].allSatisfy({ !$0.isEmpty }) else { return nil }
    return parts.prefix(3).joined(separator: ":")
}

private func reasoningParagraphs(_ text: String) -> [String] {
    text.trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: "\\n\\s*\\n", with: "\n\n", options: .regularExpression)
        .components(separatedBy: "\n\n").flatMap { part -> [String] in
            if part.range(of: "^(?:\\*\\*[^*\\n]+\\*\\*){2,}$", options: .regularExpression) != nil {
                return part.replacingOccurrences(of: "****", with: "**\n\n**").components(separatedBy: "\n\n")
            }
            return [part]
        }.filter { !$0.isEmpty }
}

private func appendSnapshot(_ previous: [String], _ next: [String]) -> [String] {
    if next.count > 1 && next.count < previous.count && Array(previous.prefix(next.count)) == next { return previous }
    var overlap = min(previous.count, next.count)
    while overlap > 0 && Array(previous.suffix(overlap)) != Array(next.prefix(overlap)) { overlap -= 1 }
    return previous + next.dropFirst(overlap)
}

func coalesceCodexReasoning(_ blocks: [ChatBlock]) -> [ChatBlock] {
    var result: [ChatBlock] = []
    var currentScope: String?
    var parts: [String] = []
    for block in blocks {
        guard case .agentReasoning(var reasoning) = block, let key = reasoningScope(reasoning.id) else {
            currentScope = nil
            result.append(block)
            continue
        }
        let next = reasoningParagraphs(reasoning.text)
        if key == currentScope, let last = result.last, case .agentReasoning(var previous) = last {
            parts = appendSnapshot(parts, next)
            previous.text = parts.joined(separator: "\n\n")
            previous.usage = reasoning.usage ?? previous.usage
            previous.model = reasoning.model ?? previous.model
            previous.durationMs = reasoning.durationMs ?? previous.durationMs
            result[result.count - 1] = .agentReasoning(previous)
        } else {
            parts = next
            reasoning.text = parts.joined(separator: "\n\n")
            currentScope = key
            result.append(.agentReasoning(reasoning))
        }
    }
    return result
}
