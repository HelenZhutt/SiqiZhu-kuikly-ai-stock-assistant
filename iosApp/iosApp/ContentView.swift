import SwiftUI
import shared

struct ContentView: View {

    var body: some View {
        // Extend beneath the device chrome, but keep the keyboard safe area so
        // the Kuikly root view is resized and the chat composer stays visible.
        KuiklyRenderViewPage(pageName: "StockList", data: [:])
            .ignoresSafeArea(.container, edges: .all)
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
