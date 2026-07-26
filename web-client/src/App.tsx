import { AriaChatWidget } from './components/AriaChatWidget';
import { MeshVisualizer } from './components/MeshVisualizer';
import { MessagePanel } from './components/MessagePanel';

function App() {
  return (
    <div className="min-h-screen bg-black text-white p-8">
      <header className="mb-8 border-b border-gray-800 pb-4">
        <h1 className="text-3xl font-bold tracking-tighter text-orange-500">MeshLink // OPS_CENTER</h1>
        <p className="text-gray-500 font-mono text-sm mt-1">Decentralized Mesh Intelligence Powered by ARIA</p>
      </header>
      
      <main className="max-w-6xl mx-auto space-y-8">
        <section>
          <MeshVisualizer />
        </section>
        
        <section>
          <MessagePanel />
        </section>
      </main>

      <AriaChatWidget />
    </div>
  );
}

export default App;
