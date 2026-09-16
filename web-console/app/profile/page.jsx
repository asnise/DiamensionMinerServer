"use client";

import dynamic from "next/dynamic";
import Skeleton from "@/components/Skeleton";

const ProfileView = dynamic(() => import("@/components/ProfileView"), {
  ssr: false,
  loading: () => (
    <div>
      <div className="card profile-hero" style={{ marginBottom: 24 }}>
        <Skeleton.ProfileHero />
      </div>
      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        <table className="data-table">
          <tbody>
            <Skeleton.Table rows={4} columns={5} />
          </tbody>
        </table>
      </div>
    </div>
  ),
});

export default function ProfilePage() {
  return <ProfileView />;
}
