# DataGrip Dataset Downloader

DataGrip 환경에서 데이터베이스 테이블의 데이터셋을 프로필 단위로 관리하고, 로컬에 CSV 및 XLSX(Excel) 형식으로 손쉽게 다운로드할 수 있도록 지원하는 플러그인 프로젝트임.

---

## 1. 주요 기능

* **편리한 인라인 편집 (Inline Table Editing)**: 프로필 목록 테이블에서 각 셀을 더블클릭(또는 클릭)하여 프로필 이름, 데이터소스, 대상 테이블명, 출력 포맷을 그 자리에서 즉시 수정 가능함.
* **마우스 우클릭 컨텍스트 메뉴**: 개별 프로필을 우클릭하여 즉시 다운로드 실행(Run Download), 상세 수정(Edit Detail...), 삭제(Delete Profile)를 신속하게 수행할 수 있음.
* **테이블명 기준 자동 파일 저장**: 다운로드 결과 파일명이 데이터베이스 테이블명(예: `t_1014_...csv` 또는 `t_1014_...xlsx`)으로 자동 저장되어 직관적인 파일 관리가 가능함.
* **대용량 스트리밍 다운로드 (OOM 방지)**: JDBC `fetchSize = 10000` 스트리밍 최적화 및 커서 제어를 통해 수백만 건 이상의 데이터도 메모리 고갈(OutOfMemoryError) 없이 안전하게 다운로드함.
* **다양한 저장 포맷 지원**:
  * **CSV**: UTF-8 BOM 형식을 기본 적용하여 Excel 한글 깨짐 문제를 해결함.
  * **XLSX**: Apache POI (`SXSSFWorkbook` 스트리밍 기법)를 활용하여 RAM 점유율을 제어하며 고속으로 엑셀 파일 변환을 완료함.
* **다운로드 완료 알림 및 폴더 추적**: 다운로드 완료 후 알림 메시지 내 **"Open Folder"** 버튼을 통해 다운로드 폴더를 시스템 탐색기로 즉시 열 수 있음.
* **설정 영속화**: IDE 재시작 시에도 `PersistentStateComponent`에 의해 구성한 프로필 설정이 자동 보존됨.

---

## 2. 시스템 아키텍처 및 데이터 흐름

```mermaid
sequenceDiagram
    participant User as 사용자 (DataGrip)
    participant Panel as 다운로더 툴윈도우
    participant Executor as DownloadExecutor
    participant DB as 데이터베이스
    participant Disk as 로컬 디스크

    User->>Panel: 프로필 선택 후 실행 버튼 클릭함
    Panel->>Executor: 다운로드 요청 (프로필 전달)
    Executor->>DB: 세션 생성 및 SELECT 쿼리 수행
    DB-->>Executor: 데이터 스트리밍 반환함
    Executor->>Disk: CSV 파일 작성함
    alt XLSX 포맷 요청인 경우
        Executor->>Disk: CSV 파일 읽음
        Executor->>Executor: Apache POI 변환 처리
        Executor->>Disk: XLSX 파일 생성함
        Executor->>Disk: 임시 CSV 파일 삭제함
    end
    Executor-->>User: 완료 알림 표시 (Notification Balloon)
```

---

## 3. 프로젝트 구조 및 주요 파일

* **빌드 스크립트**:
  * [build.gradle.kts](file:///Users/osh8242/Documents/data-download-plugin/build.gradle.kts): Gradle 빌드 스크립트 (IntelliJ Platform Plugin SDK 2.x 구성함)
  * [gradle.properties](file:///Users/osh8242/Documents/data-download-plugin/gradle.properties): JDK 17 빌드 경로 지정함
* **플러그인 설정**:
  * [plugin.xml](file:///Users/osh8242/Documents/data-download-plugin/src/main/resources/META-INF/plugin.xml): 플러그인 메타데이터 및 컴포넌트 선언함
* **소스 코드**:
  * [DataDownloadConfig.kt](file:///Users/osh8242/Documents/data-download-plugin/src/main/kotlin/com/github/plugin/datadownload/config/DataDownloadConfig.kt): 프로필 설정 영속화 서비스
  * [ProfileDialog.kt](file:///Users/osh8242/Documents/data-download-plugin/src/main/kotlin/com/github/plugin/datadownload/ui/ProfileDialog.kt): 프로필 생성/수정 UI 다이얼로그
  * [DataDownloadToolWindowFactory.kt](file:///Users/osh8242/Documents/data-download-plugin/src/main/kotlin/com/github/plugin/datadownload/ui/DataDownloadToolWindowFactory.kt): 툴윈도우 화면 프레임 및 액션 연동
  * [DownloadExecutor.kt](file:///Users/osh8242/Documents/data-download-plugin/src/main/kotlin/com/github/plugin/datadownload/service/DownloadExecutor.kt): 비동기 다운로드 및 파일 포맷 변환 처리 로직

---

## 4. 빌드 및 테스트 방법

### 1) 빌드 실행
* 프로젝트를 빌드하고 배포 가능한 플러그인 압축 파일(`.zip`)을 생성함.
```bash
./gradlew buildPlugin
```
* 빌드 결과물 경로: `build/distributions/data-download-plugin-1.0-SNAPSHOT.zip`

### 2) 로컬 디버깅 및 실행
* 개발자용 샌드박스 환경(DataGrip이 로드된 개발 전용 IDE)을 구동하여 개발한 기능을 바로 수동 테스트함.
```bash
./gradlew runIde
```
