import SwiftUI

/// Simple observable text model for the send text panel — avoids @State macro
private final class SendTextModel: ObservableObject {
    @Published var text: String = ""
}

/// Floating panel for typing text to send directly to the phone's clipboard.
struct SendTextView: View {
    @ObservedObject var controller: MenuBarController
    @ObservedObject private var model = SendTextModel()

    private let BG        = Color(red: 0.06, green: 0.06, blue: 0.06)
    private let Surface   = Color(red: 0.11, green: 0.11, blue: 0.11)
    private let Primary   = Color(red: 0.95, green: 0.95, blue: 0.95)
    private let Secondary = Color(red: 0.55, green: 0.55, blue: 0.55)
    private let AccentON  = Color(red: 0,    green: 0.88, blue: 0.44)
    private let Border    = Color(red: 0.18, green: 0.18, blue: 0.18)

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Header
            HStack(spacing: 10) {
                Image(systemName: "arrow.up.message")
                    .font(.system(size: 15, weight: .light))
                    .foregroundStyle(AccentON)
                Text("Send Text to Phone")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Primary)
                Spacer()
                Text(controller.pairedPeerName ?? "Not paired")
                    .font(.system(size: 11))
                    .foregroundStyle(Secondary)
            }

            // Text editor
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Surface)
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Border, lineWidth: 0.5))
                if model.text.isEmpty {
                    Text("Type text to send…")
                        .font(.system(size: 13))
                        .foregroundStyle(Secondary.opacity(0.6))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                }
                TextEditor(text: $model.text)
                    .font(.system(size: 13))
                    .foregroundStyle(Primary)
                    .scrollContentBackground(.hidden)
                    .background(Color.clear)
                    .padding(6)
            }
            .frame(height: 110)

            // Buttons
            HStack(spacing: 10) {
                // Paste from Mac clipboard
                Button(action: pasteMacClipboard) {
                    HStack(spacing: 5) {
                        Image(systemName: "doc.on.clipboard")
                            .font(.system(size: 11))
                        Text("Paste Mac Clipboard")
                            .font(.system(size: 12))
                    }
                    .foregroundStyle(Secondary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(Surface)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Border, lineWidth: 0.5))
                }
                .buttonStyle(.plain)

                Spacer()

                // Send button
                Button(action: { controller.sendText(model.text) }) {
                    HStack(spacing: 6) {
                        Image(systemName: "paperplane.fill")
                            .font(.system(size: 11))
                        Text("Send to Phone")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    .foregroundStyle(model.text.isEmpty ? Secondary : Color.black)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(model.text.isEmpty ? Border : AccentON)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
                .disabled(model.text.isEmpty)
                .keyboardShortcut(.return, modifiers: .command)
            }
        }
        .padding(20)
        .background(BG)
    }

    private func pasteMacClipboard() {
        if let s = NSPasteboard.general.string(forType: .string) {
            model.text = s
        }
    }
}
