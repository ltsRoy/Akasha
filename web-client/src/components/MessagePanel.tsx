import { useState } from 'react';
import { AlertCircle, FileText, CheckCircle2 } from 'lucide-react';

export function MessagePanel() {
  const [testMessage, setTestMessage] = useState('We are out of water and need medical help immediately at sector 4');
  const [sosResult, setSosResult] = useState<any>(null);
  const [isScanning, setIsScanning] = useState(false);

  const [summary, setSummary] = useState<string | null>(null);
  const [isSummarizing, setIsSummarizing] = useState(false);

  const mockMessages = [
    { sender: 'Alpha', text: 'Arrived at rally point.' },
    { sender: 'Beta', text: 'Copy that. We are 5 mins out.' },
    { sender: 'Charlie', text: 'Does anyone have spare bandages? We have a minor injury.' },
    { sender: 'Alpha', text: 'We have a medkit. Meet us at the south entrance.' }
  ];

  const handleSosScan = async () => {
    setIsScanning(true);
    setSosResult(null);
    try {
      const response = await fetch('/api/aria', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: 'SOS_SCAN', payload: testMessage })
      });
      const data = await response.json();
      try {
        // Claude might wrap JSON in markdown blocks
        let cleanStr = data.response.replace(/```json/g, '').replace(/```/g, '').trim();
        setSosResult(JSON.parse(cleanStr));
      } catch (e) {
        setSosResult({ error: 'Failed to parse ARIA response' });
      }
    } catch (e) {
      setSosResult({ error: 'API Error' });
    } finally {
      setIsScanning(false);
    }
  };

  const handleSummarize = async () => {
    setIsSummarizing(true);
    try {
      const payload = JSON.stringify(mockMessages);
      const response = await fetch('/api/aria', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: 'SUMMARIZE', payload })
      });
      const data = await response.json();
      setSummary(data.response);
    } catch (e) {
      setSummary('Failed to generate summary.');
    } finally {
      setIsSummarizing(false);
    }
  };

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-6 font-mono text-sm">
      {/* SOS Scanner */}
      <div className="bg-[#111] border border-gray-800 rounded-xl p-5">
        <div className="flex items-center gap-2 mb-4 text-orange-500">
          <AlertCircle size={18} />
          <h3 className="font-bold tracking-widest">SOS DETECTOR</h3>
        </div>
        
        <textarea
          className="w-full bg-[#1a1a1a] border border-gray-700 rounded p-3 text-gray-300 focus:outline-none focus:border-orange-500/50 mb-3"
          rows={3}
          value={testMessage}
          onChange={(e) => setTestMessage(e.target.value)}
        />
        
        <button
          onClick={handleSosScan}
          disabled={isScanning}
          className="w-full bg-orange-500 hover:bg-orange-600 disabled:opacity-50 text-black font-bold py-2 px-4 rounded transition-colors"
        >
          {isScanning ? 'SCANNING...' : 'TEST OUTGOING MESSAGE'}
        </button>

        {sosResult && (
          <div className={`mt-4 p-4 rounded border ${sosResult.isSos ? 'bg-red-500/10 border-red-500/30' : 'bg-green-500/10 border-green-500/30'}`}>
            <div className="flex items-center gap-2 mb-2">
              {sosResult.isSos ? <AlertCircle className="text-red-500" size={16} /> : <CheckCircle2 className="text-green-500" size={16} />}
              <span className={`font-bold ${sosResult.isSos ? 'text-red-500' : 'text-green-500'}`}>
                {sosResult.isSos ? 'DISTRESS DETECTED' : 'CLEARED'}
              </span>
            </div>
            {sosResult.confidence && <div className="text-gray-400 mb-1">Confidence: {sosResult.confidence}%</div>}
            <div className="text-gray-300">{sosResult.reason}</div>
          </div>
        )}
      </div>

      {/* Summarizer */}
      <div className="bg-[#111] border border-gray-800 rounded-xl p-5">
        <div className="flex items-center gap-2 mb-4 text-blue-500">
          <FileText size={18} />
          <h3 className="font-bold tracking-widest">SITUATION SUMMARY</h3>
        </div>

        <div className="bg-[#1a1a1a] border border-gray-700 rounded p-3 mb-3 h-24 overflow-y-auto">
          {mockMessages.map((m, i) => (
            <div key={i} className="mb-1">
              <span className="text-gray-500">[{m.sender}]</span> <span className="text-gray-300">{m.text}</span>
            </div>
          ))}
        </div>

        <button
          onClick={handleSummarize}
          disabled={isSummarizing}
          className="w-full bg-blue-500 hover:bg-blue-600 disabled:opacity-50 text-white font-bold py-2 px-4 rounded transition-colors"
        >
          {isSummarizing ? 'SUMMARIZING...' : 'GENERATE SITREP'}
        </button>

        {summary && (
          <div className="mt-4 p-4 bg-blue-500/10 border border-blue-500/30 rounded text-gray-300 whitespace-pre-wrap">
            {summary}
          </div>
        )}
      </div>
    </div>
  );
}
