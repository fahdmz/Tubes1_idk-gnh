# Tubes 1 Strategi Algoritma - Battlecode 2025

Ini adalah repository untuk source code implementasi bot Battlecode 2025 dengan menggunakan pendekatan Algoritma Greedy. Terdapat 3 varian bot yang telah dibuat: `mainbot`, `snowball`, dan `splasher_specialist`.

## Author
* **Al Farabi (13524086) dan Fahd Muhammad Zahid (13524078)**

## Penjelasan Singkat Algoritma Greedy

### 1. `mainbot`
- **Tower Base Building**: Greedily menyeimbangkan rasio pasukan (~50% Splasher, ~30% Soldier, ~20% Mopper). Tower selalu memprioritaskan untuk men-spawn tipe unit yang rasionya paling rendah saat ini.
- **Splasher**: Memilih target serangan secara greedy. Unit mengevaluasi semua posisi dalam jangkauan dan menghitung berapa banyak petak (tile) dalam area efek (AoE) yang kosong atau memiliki cat musuh. Splasher akan menyerang target dengan *score* tertinggi (jumlah petak terbanyak yang bisa dicat).
- **Mopper**: Pemilihan target musuh bersifat greedy. Memprioritaskan penyerangan terhadap Splasher musuh terdekat, kemudian unit musuh lainnya. Mopper juga secara greedy menyerang petak dengan cat musuh terdekat untuk membersihkannya.
- **Soldier**: Pemilihan ruin bersifat greedy. Mengevaluasi semua ruin yang terlihat dan memberikan skor (bias besar untuk ruin yang berada di "sektor" yang ditugaskan, dikurangi kuadrat jarak tempuh). Soldier akan bergerak dan membangun di ruin dengan skor tertinggi.

### 2. `snowball`
- **Tower Base Building**: Urutan pembangunan tower didasarkan pada progresi tetap yang secara greedy mengoptimalkan pertumbuhan ekonomi di awal (Money -> Paint -> Money -> Paint). Untuk *spawn*, Early Game difokuskan pada Soldier. Mid-Late game mempertahankan rasio pembentukan 1 Soldier : 1 Splasher.
- **Splasher**: Heuristik serangan mengevaluasi lokasi potensial dengan memberikan **prioritas sangat tinggi (bonus skor besar)** jika serangan mengenai area di sekitar `targetRuin` (ruin yang sedang direbut/dibangun oleh Soldier kawan), ditambah dengan jumlah petak kosong/musuh di radius ledakan AoE.
- **Soldier**: Mencari ruin dengan jarak absolut terdekat (`minDist`) yang belum memiliki tower dan tidak sedang diokupasi oleh Soldier kawan lain secara greedy.
- **Navigation (`navigateGreedy`)**: Memilih arah pergerakan secara greedy yang memaksimalkan skor berdasarkan jarak ke petak yang belum dicat/milik musuh, memberikan penalti jika terlalu dekat dengan musuh atau kawan lain, sekaligus menambahkan sedikit tarikan penjelajahan (exploration) menuju lokasi simetris di seberang peta.

### 3. `splasher_specialist`
- **Splasher Attack**: Mengiterasi semua lokasi serangan yang valid dan menghitung berapa banyak petak di dalam AoE yang kosong atau memiliki cat musuh. Unit ini menyerang target dengan jumlah *paintable tiles* tertinggi secara greedy.
- **Navigation (`greedyNavigation`)**: Menghitung `unpaintedHeuristic` untuk setiap arah pergerakan yang memungkinkan, memberi bobot berbanding terbalik dengan akar kuadrat jarak petak sasaran. Secara greedy, unit akan bergerak ke arah yang memaksimalkan akses ke wilayah yang belum dicat. Jika area sekitarnya sudah penuh dicat, unit akan bergerak secara eksklusif menuju wilayah simetris di seberang peta.

## Requirement Program
- **Java 17** (atau versi lebih baru)
- **Gradle** (Sudah disediakan *wrapper* `gradlew` / `gradlew.bat` di dalam direktori)
- Koneksi internet (dibutuhkan pada saat awal *build* untuk mengunduh dependensi Gradle dan *client* Battlecode)

## Langkah-langkah Kompilasi dan Build Program

Buka terminal / *command prompt* dan arahkan ke direktori utama tugas ini. Gunakan perintah berikut sesuai dengan spesifikasi OS (Windows menggunakan `gradlew.bat`, Linux/macOS menggunakan `./gradlew`):

1. **Kompilasi Program (Build)**
   ```bash
   # Windows
   gradlew.bat build
   
   # Linux/macOS
   ./gradlew build
   ```

2. **Menjalankan Pertandingan (Run Game)**
   Pastikan konfigurasi `gradle.properties` sudah diatur untuk map dan bot yang ingin dilawan.
   ```bash
   # Windows
   gradlew.bat run
   
   # Linux/macOS
   ./gradlew run
   ```
---

