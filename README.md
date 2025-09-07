# 🎥 Resumable Video Uploader (Core Java Prototype)

This project is a **prototype implementation of a resumable video uploader** built using **pure Core Java** (`com.sun.net.httpserver.HttpServer`).  
It demonstrates how large files (like videos) can be uploaded in chunks, resumed if interrupted, and finalized once complete — similar to how YouTube handles uploads.

---

## ✨ Features
- **Initiate Upload** → Create an upload session and empty `.part` file.  
- **Upload Chunks** → Send file pieces with `Content-Range` headers.  
- **Resume Support** → Resume uploads from the last byte if interrupted.  
- **Complete Upload** → Verify file size and rename `.part` → `.mp4`.  
- In-memory **session tracking** with `ConcurrentHashMap`.  

---

## 🚀 Getting Started

### 1. Compile & Run
```bash
javac Main.java
java Main
Server starts on: http://localhost:8080

🛠️ API Endpoints
1. Initiate Upload
bash
Copy code
POST /upload/initiate
Request (JSON):

json
Copy code
{
  "fileName": "myvideo.mp4",
  "fileSize": 10485760,
  "contentType": "video/mp4"
}
Response:

json
Copy code
{
  "uploadId": "uuid-123",
  "status": "UPLOADING",
  "uploadedBytes": 0,
  "targetPath": "uploads/uuid-123.part"
}
2. Upload Chunk
bash
Copy code
PUT /upload/{uploadId}
Headers:

css
Copy code
Content-Range: bytes 0-5242879/10485760
Body: binary chunk of the file.

Response:

json
Copy code
{
  "uploadId": "uuid-123",
  "status": "IN_PROGRESS",
  "uploadedBytes": 5242880,
  "nextExpectedByte": 5242880
}
3. Complete Upload
bash
Copy code
POST /upload/{uploadId}/complete
Response:

json
Copy code
{
  "uploadId": "uuid-123",
  "status": "UPLOADED",
  "finalPath": "uploads/uuid-123.mp4",
  "fileSize": 10485760
}
🧪 Testing the API
Using curl
Initiate

bash
Copy code
curl -X POST http://localhost:8080/upload/initiate \
  -H "Content-Type: application/json" \
  -d '{"fileName":"myvideo.mp4","fileSize":10485760,"contentType":"video/mp4"}'
Upload a chunk

bash
Copy code
curl -X PUT http://localhost:8080/upload/<uploadId> \
  -H "Content-Range: bytes 0-5242879/10485760" \
  --data-binary @chunk1
Complete

bash
Copy code
curl -X POST http://localhost:8080/upload/<uploadId>/complete
Using Postman
Initiate: POST /upload/initiate with raw JSON body.

Upload: PUT /upload/{uploadId}, add header Content-Range, select a binary file as body.

Complete: POST /upload/{uploadId}/complete.

📂 Project Structure
graphql
Copy code
.
├── Main.java        # Core Java HTTP server
├── uploads/         # Uploaded video files (auto-created)
└── README.md
⚠️ Limitations
Sessions stored in memory (lost on server restart).

JSON parsing is regex-based (replace with Gson/Jackson in production).

No authentication, validation, or quotas.

Prototype only — not production-ready.




