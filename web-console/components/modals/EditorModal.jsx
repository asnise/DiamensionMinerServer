import { useState } from 'react';

export default function EditorModal({ kind, initialData, onClose }) {
  const isEditing = !!initialData;
  const [formData, setFormData] = useState(initialData || { id: '', payload: {} });

  const handleSubmit = async (e) => {
    e.preventDefault();
    try {
      const res = await fetch(`/api/content/${kind}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ definition_id: formData.id, payload: formData.payload })
      });
      if (res.ok) onClose(true);
      else alert('Failed to save');
    } catch (err) {
      console.error(err);
    }
  };

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <form onSubmit={handleSubmit}>
          <div className="modal-header">
            <h3>{isEditing ? 'Edit Content' : 'Create Content'}</h3>
            <button type="button" onClick={() => onClose(false)} style={{border:'none',background:'none',cursor:'pointer'}}>✕</button>
          </div>
          <div className="modal-body">
            <div className="form-group">
              <label>ID</label>
              <input 
                type="text" 
                value={formData.id} 
                onChange={e => setFormData({...formData, id: e.target.value})} 
                disabled={isEditing} 
                required 
              />
            </div>
            <div className="form-group">
              <label>Payload (JSON)</label>
              <textarea 
                rows={10} 
                value={JSON.stringify(formData.payload, null, 2)} 
                onChange={e => {
                  try { setFormData({...formData, payload: JSON.parse(e.target.value)}) } catch(e){}
                }}
                style={{width: '100%', fontFamily: 'monospace', padding: '10px'}}
              />
            </div>
          </div>
          <div className="modal-footer">
            <button type="button" className="btn-secondary" onClick={() => onClose(false)}>Cancel</button>
            <button type="submit" className="btn-primary">Save Changes</button>
          </div>
        </form>
      </div>
    </div>
  );
}
