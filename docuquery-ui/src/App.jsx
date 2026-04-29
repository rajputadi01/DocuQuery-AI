import { useState, useRef, useEffect } from 'react';
import axios from 'axios';
import { FileUp, FileText, Loader2, Send, Trash2, ChevronDown, ChevronUp, Zap, Eraser, AlertCircle, Shield, MessageSquare, Sparkles } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import './App.css';

const ChatMessage = ({ msg }) => {
  const [showSources, setShowSources] = useState(false);
  const isUser = msg.sender === 'user';

  return (
    <div className={`chat-bubble ${isUser ? 'user' : 'ai'}`}>
      
      <div className="markdown-content">
        <ReactMarkdown>{msg.text}</ReactMarkdown>
      </div>
      
      {!isUser && msg.confidenceScore > 0 && (
        <div style={{ borderTop: '1px solid #e2e8f0', marginTop: '0.75rem', paddingTop: '0.5rem' }}>
          <div className="confidence-badge">
            <Zap size={12} fill="currentColor" />
            Relevance Score: {(msg.confidenceScore * 100).toFixed(1)}%
          </div>
          
          {msg.sources && msg.sources.length > 0 && (
            <div style={{ marginTop: '0.5rem' }}>
              <button className="sources-toggle" onClick={() => setShowSources(!showSources)}>
                {showSources ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                {showSources ? 'Hide Sources' : 'View Extracted Sources'}
              </button>
              
              {showSources && (
                <div className="sources-content">
                  {msg.sources.map((source, idx) => (
                    <div key={idx} style={{ marginBottom: '0.5rem', paddingBottom: '0.5rem', borderBottom: '1px dashed #cbd5e1' }}>
                      <em>"...{source.trim()}..."</em>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

function App() {
  const [documents, setDocuments] = useState([]); 
  const [activeDocumentId, setActiveDocumentId] = useState(null);
  const [activeTab, setActiveTab] = useState('summary');
  
  // Decoupled loading states for better UX
  const [loading, setLoading] = useState(false);
  const [isUploading, setIsUploading] = useState(false); // NEW
  
  const [summaries, setSummaries] = useState({});
  const [chatHistories, setChatHistories] = useState({}); 
  
  const [file, setFile] = useState(null);
  const [fileError, setFileError] = useState(''); 
  const [question, setQuestion] = useState('');
  
  const chatEndRef = useRef(null);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [chatHistories, activeDocumentId]);

  const handleFileChange = (e) => {
    const selectedFile = e.target.files[0];
    setFileError(''); 
    
    if (selectedFile) {
      if (selectedFile.type !== 'application/pdf') {
        setFileError('Invalid file type. Please upload a PDF document.');
        setFile(null);
        e.target.value = null; 
      } else {
        setFile(selectedFile);
      }
    }
  };

  const handleUpload = async () => {
    if (!file) return;
    setIsUploading(true); // Trigger the skeleton loader
    
    const formData = new FormData();
    formData.append('file', file);

    try {
      const response = await axios.post('https://docuquery-api-idh9.onrender.com/api/documents/upload', formData);
      const newDocId = response.data.documentId;
      
      const now = new Date();
      const dateStr = now.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
      const timeStr = now.toLocaleTimeString(undefined, { hour: '2-digit', minute:'2-digit' });
      
      setDocuments([{
        id: newDocId,
        name: file.name,
        time: `${dateStr}, ${timeStr}` 
      }, ...documents]);
      
      setActiveDocumentId(newDocId);
      setActiveTab('summary'); // Auto-switch to summary on new upload
      setFile(null); 
      setChatHistories(prev => ({...prev, [newDocId]: []}));
      
    } catch (error) {
      alert("Upload failed: " + (error.response?.data?.message || error.message));
    } finally {
      setIsUploading(false); // Remove skeleton loader
    }
  };

  const handleDelete = async (e, idToDelete) => {
    e.stopPropagation(); 
    if (!window.confirm("Are you sure you want to delete this document from the AI memory?")) return;
    
    try {
      await axios.delete(`https://docuquery-api-idh9.onrender.com/api/documents/${idToDelete}`);
      setDocuments(documents.filter(doc => doc.id !== idToDelete));
      
      const newSummaries = {...summaries}; delete newSummaries[idToDelete]; setSummaries(newSummaries);
      const newChats = {...chatHistories}; delete newChats[idToDelete]; setChatHistories(newChats);
      
      if (activeDocumentId === idToDelete) {
        setActiveDocumentId(documents.length > 1 ? documents.find(d => d.id !== idToDelete).id : null);
      }
    } catch (error) {
      alert("Failed to delete: " + (error.response?.data?.message || error.message));
    }
  };

  const handleGetSummary = async () => {
    if (!activeDocumentId) return;
    setLoading(true);
    try {
      const response = await axios.get(`https://docuquery-api-idh9.onrender.com/api/documents/${activeDocumentId}/summary`);
      setSummaries(prev => ({...prev, [activeDocumentId]: response.data}));
    } catch (error) {
      alert("Failed to get summary: " + (error.response?.data?.message || error.message));
    } finally {
      setLoading(false);
    }
  };

  const handleAskQuestion = async () => {
    if (!activeDocumentId || !question.trim()) return;
    
    const userQ = question;
    setQuestion(''); 
    
    const newChatMsg = { sender: 'user', text: userQ };
    const aiPlaceholder = { sender: 'ai', text: '', confidenceScore: 0, sources: [] };

    setChatHistories(prev => ({
      ...prev,
      [activeDocumentId]: [...(prev[activeDocumentId] || []), newChatMsg, aiPlaceholder]
    }));

    setLoading(true);
    try {
      const response = await fetch(`https://docuquery-api-idh9.onrender.com/api/documents/${activeDocumentId}/query`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: userQ })
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.message || 'Server error');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8");
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        let boundary = buffer.indexOf('\n\n');

        while (boundary !== -1) {
          const message = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);

          if (message.startsWith('data:')) {
            const dataStr = message.substring(5).trim();
            if (dataStr) {
              const data = JSON.parse(dataStr);

              if (data.type === 'metadata') {
                setChatHistories(prev => {
                  const current = prev[activeDocumentId];
                  const updated = [...current];
                  updated[updated.length - 1] = {
                    ...updated[updated.length - 1],
                    confidenceScore: data.score,
                    sources: data.sources
                  };
                  return { ...prev, [activeDocumentId]: updated };
                });
              } 
              else if (data.type === 'token') {
                setChatHistories(prev => {
                  const current = prev[activeDocumentId];
                  const updated = [...current];
                  updated[updated.length - 1] = {
                    ...updated[updated.length - 1],
                    text: updated[updated.length - 1].text + data.content
                  };
                  return { ...prev, [activeDocumentId]: updated };
                });
              } 
              else if (data.type === 'complete') {
                setLoading(false); 
              }
            }
          }
          boundary = buffer.indexOf('\n\n');
        }
      }
    } catch (error) {
      alert("Query failed: " + error.message);
      setChatHistories(prev => ({
        ...prev,
        [activeDocumentId]: prev[activeDocumentId].slice(0, -2)
      }));
    } finally {
      setLoading(false);
    }
  };

  const handleResetChat = async () => {
    if (!activeDocumentId) return;
    if (!window.confirm("Are you sure you want to clear the conversation history for this document?")) return;

    try {
      await axios.delete(`https://docuquery-api-idh9.onrender.com/api/documents/${activeDocumentId}/chat/reset`);
      setChatHistories(prev => ({ ...prev, [activeDocumentId]: [] }));
    } catch (error) {
      alert("Failed to reset chat: " + (error.response?.data?.message || error.message));
    }
  };

  const activeSummary = summaries[activeDocumentId];
  const activeChat = chatHistories[activeDocumentId] || [];

  return (
    <div className="app-container">
      <div className="left-panel">
        <div className="header-title">
          <FileText color="#2563eb" /> DocuQuery AI
        </div>
        
        <div className="upload-box">
          <input 
            type="file" 
            accept=".pdf"
            onChange={handleFileChange} 
            style={{marginBottom: '1rem', width: '100%'}}
          />
          
          {fileError && (
            <div style={{ color: '#ef4444', fontSize: '0.8rem', marginBottom: '1rem', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.25rem' }}>
              <AlertCircle size={14} /> {fileError}
            </div>
          )}

          <button className="btn" onClick={handleUpload} disabled={!file || fileError || isUploading}>
            {isUploading ? <Loader2 className="lucide-spin" /> : <FileUp />}
            {isUploading ? "Extracting Vectors..." : "Upload & Process"}
          </button>
        </div>

        {documents.length > 0 && (
          <>
            <div className="doc-list-header">Active Memory ({documents.length}/5)</div>
            <div className="doc-list">
              {documents.map((doc) => (
                <div 
                  key={doc.id} 
                  className={`doc-item ${activeDocumentId === doc.id ? 'active' : ''}`}
                  onClick={() => setActiveDocumentId(doc.id)}
                >
                  <div className="doc-info">
                    <span className="doc-name">{doc.name}</span>
                    <span className="doc-time">
                      <span style={{color: '#94a3b8', fontSize: '0.65rem'}}>#{doc.id.substring(0,6)} </span>
                      {doc.time}
                    </span>
                  </div>
                  <button 
                    className="delete-btn" 
                    title="Delete from memory"
                    onClick={(e) => handleDelete(e, doc.id)}
                  >
                    <Trash2 size={18} />
                  </button>
                </div>
              ))}
            </div>
          </>
        )}
      </div>

      <div className="right-panel">
        <div className="tabs">
          <div 
            className={`tab ${(activeTab === 'summary' && (activeDocumentId || isUploading)) ? 'active' : ''}`}
            onClick={() => setActiveTab('summary')}
            style={{ pointerEvents: (activeDocumentId || isUploading) ? 'auto' : 'none' }}
          >
            Structured Output
          </div>
          <div 
            className={`tab ${(activeTab === 'chat' && (activeDocumentId || isUploading)) ? 'active' : ''}`}
            onClick={() => setActiveTab('chat')}
            style={{ pointerEvents: (activeDocumentId || isUploading) ? 'auto' : 'none' }}
          >
            RAG Assistant
          </div>
        </div>

        <div className="content-card">
          
          {/* NEW: 1. Loading Skeleton View */}
          {isUploading ? (
            <div className="skeleton-container">
              <div className="skeleton-header"></div>
              <div className="skeleton-block">
                <div className="skeleton-line"></div>
                <div className="skeleton-line"></div>
                <div className="skeleton-line short"></div>
              </div>
              <div className="skeleton-header" style={{width: '25%', marginTop: '1rem'}}></div>
              <div className="skeleton-block">
                <div className="skeleton-line"></div>
                <div className="skeleton-line short"></div>
              </div>
              <div style={{textAlign: 'center', color: '#94a3b8', marginTop: 'auto', fontSize: '0.875rem'}}>
                <Loader2 className="lucide-spin" size={16} style={{display: 'inline', marginRight: '0.5rem', verticalAlign: 'text-bottom'}} />
                Parsing PDF and Generating Embeddings...
              </div>
            </div>
          ) : 
          
          /* NEW: 2. Landing State View */
          !activeDocumentId ? (
            <div className="landing-state">
              <Sparkles size={48} color="#2563eb" style={{marginBottom: '1rem', opacity: 0.8}} />
              <h2>Enterprise Document Intelligence</h2>
              <p>Upload a PDF to instantly extract structured metadata and engage in context-aware, fully auditable conversations.</p>
              
              <ul className="feature-list">
                <li className="feature-item">
                  <Zap color="#2563eb" size={20}/>
                  <span><strong>Instant Extraction:</strong> Converts unstructured text into precise JSON metadata.</span>
                </li>
                <li className="feature-item">
                  <MessageSquare color="#2563eb" size={20}/>
                  <span><strong>Stateful Memory:</strong> Context-aware RAG chat supporting multi-turn conversations.</span>
                </li>
                <li className="feature-item">
                  <Shield color="#2563eb" size={20}/>
                  <span><strong>Zero Hallucinations:</strong> Every answer is backed by a vector confidence score and raw source text.</span>
                </li>
              </ul>
            </div>
          ) : (
            
            /* 3. Normal Active Content View */
            <>
              {activeTab === 'summary' && (
                <div style={{overflowY: 'auto', paddingRight: '1rem'}}>
                  <button className="btn" onClick={handleGetSummary} disabled={loading} style={{width: 'auto'}}>
                    {loading && !activeSummary ? <Loader2 className="lucide-spin"/> : null} 
                    {activeSummary ? "Refresh Metadata" : "Generate JSON Metadata"}
                  </button>
                  
                  {activeSummary && (
                    <div style={{marginTop: '2rem'}}>
                      <h3 style={{marginBottom: '0.5rem', color: '#0f172a'}}>Document Classification:</h3>
                      <p style={{color: '#2563eb', fontWeight: 'bold', fontSize: '1.1rem'}}>{activeSummary.documentType}</p>
                      
                      <h3 style={{marginTop: '1.5rem', marginBottom: '0.5rem', color: '#0f172a'}}>Executive Summary:</h3>
                      <p style={{lineHeight: '1.6'}}>{activeSummary.shortSummary}</p>
                      
                      <h3 style={{marginTop: '1.5rem', marginBottom: '0.5rem', color: '#0f172a'}}>Extracted Entities:</h3>
                      <ul style={{paddingLeft: '1.5rem', lineHeight: '1.6'}}>
                        {activeSummary.keyEntities.map((entity, idx) => (
                          <li key={idx}>{entity}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              )}

              {activeTab === 'chat' && (
                <div className="chat-container">
                  {activeChat.length > 0 && (
                    <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '0.5rem' }}>
                      <button 
                        onClick={handleResetChat}
                        style={{ background: 'none', border: 'none', color: '#ef4444', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '0.25rem', fontSize: '0.8rem', fontWeight: '600' }}
                      >
                        <Eraser size={14} /> Clear Chat
                      </button>
                    </div>
                  )}

                  <div className="chat-history">
                    {activeChat.length === 0 ? (
                      <div style={{color: '#94a3b8', textAlign: 'center', marginTop: '2rem'}}>
                        I have analyzed the document. What would you like to know?
                      </div>
                    ) : (
                      activeChat.map((msg, idx) => <ChatMessage key={idx} msg={msg} />)
                    )}
                    <div ref={chatEndRef} /> 
                  </div>

                  <div className="chat-input-group">
                    <input 
                      type="text" 
                      className="chat-input"
                      placeholder="Ask a question about this document..."
                      value={question}
                      onChange={(e) => setQuestion(e.target.value)}
                      onKeyDown={(e) => e.key === 'Enter' && handleAskQuestion()}
                      disabled={loading}
                    />
                    <button 
                      className="btn" 
                      style={{width: 'auto'}} 
                      onClick={handleAskQuestion}
                      disabled={!question.trim() || loading}
                    >
                      {loading ? <Loader2 className="lucide-spin"/> : <Send size={18}/>}
                    </button>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

export default App;