// The only bridge between the page and Node: a few file functions, nothing else.
const { contextBridge, ipcRenderer, webUtils } = require('electron');
const path = require('path');

contextBridge.exposeInMainWorld('studio', {
    openDialog: (opts) => ipcRenderer.invoke('dialog:open', opts),
    readText: (p) => ipcRenderer.invoke('fs:readText', p),
    readBase64: (p) => ipcRenderer.invoke('fs:readBase64', p),
    exists: (p) => ipcRenderer.invoke('fs:exists', p),
    list: (dir) => ipcRenderer.invoke('fs:list', dir),
    watch: (files) => ipcRenderer.invoke('fs:watch', files),
    onFileChanged: (fn) => ipcRenderer.on('file:changed', (_e, f) => fn(f)),
    info: () => ipcRenderer.invoke('app:info'),
    toggleDevTools: () => ipcRenderer.invoke('app:devtools'),
    paths: () => ipcRenderer.invoke('app:paths'),
    readJsonTree: (dir) => ipcRenderer.invoke('fs:readJsonTree', dir),
    writeJson: (p, text) => ipcRenderer.invoke('fs:writeJson', p, text),
    saveDialog: (opts) => ipcRenderer.invoke('dialog:save', opts),
    validate: (kind, data) => ipcRenderer.invoke('schema:validate', kind, data),
    schema: (name) => ipcRenderer.invoke('schema:get', name),
    exportModel: (job) => ipcRenderer.invoke('export:model', job),
    project: {
        create: (dir, name) => ipcRenderer.invoke('project:create', dir, name),
        read: (p) => ipcRenderer.invoke('project:read', p),
        write: (dir, data) => ipcRenderer.invoke('project:write', dir, data),
        importModel: (dir, file) => ipcRenderer.invoke('project:importModel', dir, file),
    },
    showFolder: (p) => ipcRenderer.invoke('shell:open', p),
    quit: () => ipcRenderer.invoke('app:quit'),
    live: {
        call: (link, method, apiPath, body) => ipcRenderer.invoke('live:call', link, method, apiPath, body),
        send: (link, job) => ipcRenderer.invoke('live:send', link, job),
        tokenFrom: (serverDir) => ipcRenderer.invoke('live:tokenFrom', serverDir),
    },
    mc: {
        info: () => ipcRenderer.invoke('mc:info'),
        pickJar: () => ipcRenderer.invoke('mc:pickJar'),
        list: (prefix) => ipcRenderer.invoke('mc:list', prefix),
        read: (name) => ipcRenderer.invoke('mc:read', name),
    },
    // Path of a file dropped onto the window (File.path is gone in recent Electron).
    pathOf: (file) => webUtils.getPathForFile(file),
    path: {
        join: (...p) => path.join(...p),
        dirname: (p) => path.dirname(p),
        basename: (p, ext) => path.basename(p, ext),
        resolve: (...p) => path.resolve(...p),
        sep: path.sep,
    },
});
