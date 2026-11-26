# Rhizome

> English version: [README.md](README.md)

Kotlin 코루틴 기반의 구조적 동시성으로 구현된 BitTorrent 클라이언트 및 Kademlia DHT 노드입니다. 모든 `.torrent` 파일, 피어, 트래커, DHT 노드가 악의적일 수 있다는 전제 하에 방어적으로 설계되었습니다.

## 주요 기능 (`v0.3.0`)

- **프로토콜 지원**: BEP 3 (피어 와이어/HTTP 트래커), BEP 5 (Kademlia DHT), BEP 9/10 (확장 프로토콜 및 마그넷 메타데이터), BEP 11 (PEX 피어 교환), BEP 15 (UDP 트래커 announce/scrape), BEP 23 (압축 피어 목록), BEP 27 (프라이빗 토렌트), BEP 48 (HTTP/HTTPS 트래커 scrape).
- **보안 강화**: 경로 순회 및 심볼릭 링크 차단, 사설 IP/SSRF 차단(`AddressPolicy`), 리다이렉트 금지, 메시지/메타데이터/트래커 응답 크기 상한, 요청하지 않은 블록 거부, 손상 피스 전송 피어 자동 차단.
- **대역폭 제한기**: `ClientConfig.maxDownloadBytesPerSec` / `maxUploadBytesPerSec` 및 CLI `--max-down` / `--max-up` 옵션으로 코루틴 기반 토큰 버킷 속도 제한을 제공합니다.
- **CLI 및 JSON 출력**: `info`, `magnet`, `create`, `verify`, `scrape`, `download`, `lookup`, `version` 모든 명령에서 `--json` 출력을 지원합니다.

## 빠른 시작

```sh
./gradlew installDist
build/install/rhizome/bin/rhizome info ubuntu.torrent --json
build/install/rhizome/bin/rhizome magnet ubuntu.torrent
build/install/rhizome/bin/rhizome scrape ubuntu.torrent --json
build/install/rhizome/bin/rhizome download ubuntu.torrent --out ./downloads --max-down 2M --max-up 512K
```

## 빌드 및 테스트

```sh
./gradlew build --no-daemon
```

## 라이선스

MIT — [LICENSE](LICENSE) 참조.
