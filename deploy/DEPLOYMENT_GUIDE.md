# Hướng dẫn Triển khai & CI/CD Queue Service

Server mục tiêu: **Ubuntu 24.04 LTS** (IP: `163.61.72.183`, Hostname: `vm08181524.bnixvps.io.vn`)  
Tài khoản SSH: `devops` (hoặc `root`)  
Container Name: `erp-queue` (Port nội bộ `127.0.0.1:8090`)

---

## 1. Cấu trúc Triển khai

```
queue-service/
├── .github/
│   └── workflows/
│       └── ci-cd.yml                # GitHub Actions: Build & Deploy tự động khi push nhánh `dev`
├── deploy/
│   ├── DEPLOYMENT_GUIDE.md          # Tài liệu này
│   ├── docker/
│   │   └── queue.yml                # Cấu hình Docker Compose cho erp-queue
│   └── scripts/
│       ├── deploy.sh                # Script deploy tự động + healthcheck + auto rollback
│       ├── rollback.sh              # Script rollback thủ công về bản stable trước
│       ├── 02-build-push.sh         # Script build từ local & đẩy qua SSH (Bash)
│       └── 02-build-push.ps1        # Script build từ local & đẩy qua SSH (PowerShell)
└── Dockerfile                       # Multi-stage Dockerfile độc lập với core-model
```

---

## 2. Thiết lập GitHub Secrets cho CI/CD

Để GitHub Actions có thể SSH vào server và deploy tự động, bạn cần cấu hình các Secrets trong Repository:

Vào GitHub: **`https://github.com/F-B-Chain-ERP/queue-service/settings/secrets/actions`**

### Cách 1: Thêm nhanh bằng GitHub CLI (`gh`)
Nếu máy đã cài `gh` CLI:
```bash
# Đăng nhập gh nếu chưa: gh auth login

gh secret set SSH_HOST --body "163.61.72.183" -R F-B-Chain-ERP/queue-service
gh secret set SSH_USER --body "devops" -R F-B-Chain-ERP/queue-service
gh secret set SSH_PORT --body "22" -R F-B-Chain-ERP/queue-service
gh secret set SSH_PRIVATE_KEY < ~/.ssh/id_rsa -R F-B-Chain-ERP/queue-service

# (Tùy chọn) Personal Access Token có quyền đọc repo core-model nếu repo là private:
gh secret set GH_PAT --body "ghp_xxxxxxxxxxxx" -R F-B-Chain-ERP/queue-service

# (Tùy chọn) Webhook Discord nhận thông báo deploy:
gh secret set DISCORD_WEBHOOK --body "https://discord.com/api/webhooks/..." -R F-B-Chain-ERP/queue-service
```

### Cách 2: Thêm qua giao diện Web GitHub
Tạo các **Repository secrets** sau:
| Secret Name | Giá trị mẫu | Bắt buộc? | Mô tả |
|---|---|---|---|
| `SSH_HOST` | `163.61.72.183` | ✅ | IP máy chủ |
| `SSH_USER` | `devops` | ✅ | User SSH trên máy chủ |
| `SSH_PRIVATE_KEY` | `-----BEGIN OPENSSH PRIVATE KEY-----...` | ✅ | Private SSH key tương ứng với key trên server |
| `SSH_PORT` | `22` | ⚪ | Port SSH (mặc định 22) |
| `GH_PAT` | `ghp_xxxxxxxx` | ⚪ | Token clone `core-model` nếu repo private |
| `DISCORD_WEBHOOK` | `https://discord.com/api/webhooks/...` | ⚪ | Gửi thông báo deploy lên Discord |

---

## 3. Tạo SSH Key pair (nếu chưa có)

Nếu server chưa có cặp SSH key riêng cho GitHub Actions:
```bash
# 1. Trên máy dev (hoặc server):
ssh-keygen -t ed25519 -C "github-actions-queue-service" -f ~/.ssh/id_ed25519_deploy -N ""

# 2. Đưa public key lên server vm08181524:
ssh-copy-id -i ~/.ssh/id_ed25519_deploy.pub devops@163.61.72.183

# 3. Lấy private key để dán vào GitHub Secret (SSH_PRIVATE_KEY):
cat ~/.ssh/id_ed25519_deploy
```

---

## 4. Triển khai Lần đầu trên Server (Thủ công)

### Bước 1: SSH vào server và clone/chuẩn bị thư mục
```bash
ssh devops@163.61.72.183

# Tạo thư mục queue-service (nếu chưa có)
mkdir -p ~/queue-service
cd ~/queue-service

# Clone repo (hoặc kéo nhánh dev về)
git clone -b dev https://github.com/F-B-Chain-ERP/queue-service.git .
chmod +x deploy/scripts/*.sh
```

### Bước 2: Chạy Deploy lần đầu
```bash
# Đăng nhập GHCR
echo "<GITHUB_TOKEN_HOAC_PAT>" | docker login ghcr.io -u <github_username> --password-stdin

# Chạy deploy script với tag mong muốn (ví dụ dev hoặc latest)
bash deploy/scripts/deploy.sh ghcr.io/f-b-chain-erp/erp-queue dev
```

### Bước 3: Kiểm tra trạng thái
```bash
# Xem container
docker ps | grep erp-queue

# Kiểm tra healthcheck
curl -s http://127.0.0.1:8090/actuator/health

# Xem logs container
docker logs -f erp-queue
```

---

## 5. Quy trình Vận hành CI/CD tự động

1. Dev push commit vào nhánh `dev` của repo `queue-service`.
2. GitHub Actions tự động kích hoạt pipeline:
   - **Job 1 (Validate)**: Biên dịch `core-model` + `queue-service` với Java 21 Temurin.
   - **Job 2 (Build & Push)**: Build Docker image đa tầng và push lên `ghcr.io/f-b-chain-erp/erp-queue` với tag `sha-<short_sha>`, `dev`, `latest`.
   - **Job 3 (Deploy)**: SSH vào server, pull image mới, restart container, chạy Health Check (90s). Nếu fail, tự động kích hoạt **Rollback**.
   - **Job 4 (Notify)**: Báo kết quả trạng thái deploy qua Discord.
