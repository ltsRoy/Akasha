import { useState, useRef, useEffect } from 'react';
import { MessageSquare, X, Send } from 'lucide-react';

interface ChatMessage {
  id: string;
  role: 'user' | 'aria';
  text: string;
  isSos?: boolean;
}

export function AriaChatWidget() {
  const [isOpen, setIsOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([
    { id: 'init', role: 'aria', text: 'ARIA online. How can I assist you with survival protocols or mesh status today?' }
  ]);
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const handleSend = async () => {
    if (!input.trim() || isLoading) return;
    
    const userMsg = input.trim();
    setInput('');
    
    const newMessages: ChatMessage[] = [
      ...messages,
      { id: Date.now().toString(), role: 'user', text: userMsg }
    ];
    setMessages(newMessages);
    setIsLoading(true);

    try {
      const response = await fetch('/api/aria', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: 'CHAT', payload: userMsg })
      });
      
      const data = await response.json();
      
      setMessages([
        ...newMessages,
        { id: (Date.now() + 1).toString(), role: 'aria', text: data.response }
      ]);
    } catch (error) {
      setMessages([
        ...newMessages,
        { id: (Date.now() + 1).toString(), role: 'aria', text: 'Error connecting to ARIA core.' }
      ]);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="fixed bottom-6 right-6 z-50 font-mono">
      {!isOpen && (
        <button 
          onClick={() => setIsOpen(true)}
          className="bg-orange-500 hover:bg-orange-600 text-black p-4 rounded-full shadow-lg shadow-orange-500/20 transition-all flex items-center justify-center"
        >
          <MessageSquare size={24} />
        </button>
      )}

      {isOpen && (
        <div className="bg-[#111] border border-orange-500/30 w-80 sm:w-96 rounded-xl shadow-2xl flex flex-col overflow-hidden h-[500px]">
          {/* Header */}
          <div className="bg-[#1a1a1a] border-b border-orange-500/20 p-4 flex justify-between items-center">
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-orange-500 animate-pulse"></div>
              <h3 className="text-orange-500 font-bold text-sm tracking-widest">ARIA SYSTEM</h3>
            </div>
            <button onClick={() => setIsOpen(false)} className="text-gray-400 hover:text-white">
              <X size={18} />
            </button>
          </div>

          {/* Chat Area */}
          <div className="flex-1 overflow-y-auto p-4 space-y-4 bg-gradient-to-b from-[#111] to-[#0a0a0a]">
            {messages.map((msg) => (
              <div key={msg.id} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[85%] rounded-lg p-3 text-sm ${
                  msg.role === 'user' 
                    ? 'bg-orange-500/10 text-orange-100 border border-orange-500/20 rounded-tr-none' 
                    : 'bg-[#222] text-gray-200 border border-gray-700 rounded-tl-none'
                }`}>
                  {msg.role === 'aria' && <div className="text-xs text-orange-500/70 mb-1 font-bold">ARIA</div>}
                  {msg.text}
                </div>
              </div>
            ))}
            {isLoading && (
              <div className="flex justify-start">
                <div className="bg-[#222] border border-gray-700 rounded-lg rounded-tl-none p-3 text-sm text-gray-400 flex items-center gap-2">
                  <div className="w-1.5 h-1.5 bg-orange-500 rounded-full animate-bounce"></div>
                  <div className="w-1.5 h-1.5 bg-orange-500 rounded-full animate-bounce delay-100"></div>
                  <div className="w-1.5 h-1.5 bg-orange-500 rounded-full animate-bounce delay-200"></div>
                </div>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>

          {/* Input Area */}
          <div className="p-3 border-t border-orange-500/20 bg-[#1a1a1a]">
            <form onSubmit={(e) => { e.preventDefault(); handleSend(); }} className="flex gap-2">
              <input
                type="text"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="Ask about protocols..."
                className="flex-1 bg-[#222] border border-gray-700 rounded p-2 text-sm text-white focus:outline-none focus:border-orange-500/50"
              />
              <button 
                type="submit"
                disabled={isLoading || !input.trim()}
                className="bg-orange-500 hover:bg-orange-600 disabled:opacity-50 disabled:hover:bg-orange-500 text-black p-2 rounded transition-colors"
              >
                <Send size={18} />
              </button>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
