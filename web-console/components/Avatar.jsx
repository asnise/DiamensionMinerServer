"use client";

import { useState } from 'react';
import Image from 'next/image';

const SKIN_CDN = 'https://mc-heads.net/avatar';

export default function Avatar({ uuid, name, size = 32 }) {
  const [failed, setFailed] = useState(false);
  const initials = (name || '?').slice(0, 2).toUpperCase();

  if (failed || !uuid) {
    return (
      <span className="avatar" style={{ width: size, height: size, fontSize: size * 0.36 }}>
        {initials}
      </span>
    );
  }

  return (
    <span className="avatar" style={{ width: size, height: size }}>
      <Image
        src={`${SKIN_CDN}/${uuid}/${size * 2}`}
        alt={name}
        width={size * 2}
        height={size * 2}
        unoptimized
        onError={() => setFailed(true)}
      />
    </span>
  );
}