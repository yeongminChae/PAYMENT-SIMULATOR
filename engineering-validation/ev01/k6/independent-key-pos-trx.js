// seq 0001..9999를 한 번만 사용한다. 고갈된 번호는 재사용하지 않는다.
export function makePosTrx(store, date, base, vus, vu, iteration) {
  // 1-based VU는 posNo, 0-based iteration은 seq로 옮긴다. 예약·DB 조회는 호출 전에 완료해야 한다.
  // 날짜는 8자리 형식만 확인한다. 실제 달력 유효성은 wrapper가 생성하는 UTC 날짜에 의존한다.
  if (!/^\d{4}$/.test(store) || !/^\d{8}$/.test(date) ||
      !Number.isInteger(base) || base < 0 || base + vus > 10000 ||
      !Number.isInteger(vus) || vus < 1 || !Number.isInteger(vu) || vu < 1 || vu > vus ||
      !Number.isInteger(iteration) || iteration < 0 || iteration >= 9999) {
    throw new Error('posTrx range exhausted or invalid input');
  }
  return `${store}-${date}-${String(base + vu - 1).padStart(4, '0')}-${String(iteration + 1).padStart(4, '0')}`;
}
