import { useState, useCallback } from 'react';
import ForceGraph2D from 'react-force-graph-2d';
import { Network, Activity } from 'lucide-react';

interface Node {
  id: string;
  name?: string;
  val: number;
}


export function MeshVisualizer() {
  // Mock data for demo, in reality fetch from /api/peers
  const data = {
    nodes: [
      { id: 'me', name: 'My Device', val: 10 },
      { id: 'node1', name: 'Alpha Station', val: 5 },
      { id: 'node2', name: 'Beta Post', val: 5 },
      { id: 'node3', name: 'Charlie Medical', val: 8 },
    ],
    links: [
      { source: 'me', target: 'node1' },
      { source: 'node1', target: 'node2' },
      { source: 'node2', target: 'node3' },
      { source: 'me', target: 'node3' },
    ]
  };

  const [explanation, setExplanation] = useState<string | null>(null);
  const [loadingNode, setLoadingNode] = useState<string | null>(null);

  const handleNodeClick = useCallback(async (node: Node) => {
    setLoadingNode(node.id);
    setExplanation(null);
    try {
      const nodeData = JSON.stringify({
        nodeId: node.id,
        name: node.name,
        connections: data.links.filter(l => l.source === node.id || l.target === node.id)
      });

      const response = await fetch('/api/aria', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: 'NODE_EXPLAIN', payload: nodeData })
      });
      const resData = await response.json();
      setExplanation(resData.response);
    } catch (e) {
      setExplanation('Error analyzing node health.');
    } finally {
      setLoadingNode(null);
    }
  }, []);

  return (
    <div className="relative w-full h-[600px] bg-[#0a0a0a] border border-gray-800 rounded-xl overflow-hidden font-mono">
      <div className="absolute top-4 left-4 z-10 flex items-center gap-2 bg-[#111]/80 backdrop-blur border border-gray-800 p-2 rounded-lg">
        <Network size={16} className="text-orange-500" />
        <span className="text-xs text-gray-300 tracking-wider">TOPOLOGY MAP</span>
      </div>

      <ForceGraph2D
        graphData={data}
        nodeLabel="name"
        nodeColor={(node: any) => node.id === 'me' ? '#f97316' : '#3b82f6'}
        backgroundColor="#0a0a0a"
        linkColor={() => '#333'}
        onNodeClick={handleNodeClick}
      />

      {(explanation || loadingNode) && (
        <div className="absolute bottom-4 left-4 right-4 z-10 bg-[#111]/90 backdrop-blur border border-orange-500/30 p-4 rounded-xl shadow-2xl animate-in slide-in-from-bottom-4">
          <div className="flex items-start gap-3">
            <Activity className={`text-orange-500 mt-1 ${loadingNode ? 'animate-pulse' : ''}`} size={20} />
            <div>
              <h4 className="text-orange-500 text-xs font-bold tracking-widest mb-1">ARIA NODE ANALYSIS</h4>
              <p className="text-sm text-gray-300 leading-relaxed">
                {loadingNode ? 'Analyzing topology data...' : explanation}
              </p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
