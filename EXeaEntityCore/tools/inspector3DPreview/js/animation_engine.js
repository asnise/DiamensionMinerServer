/**
 * EXea Animation Engine
 * 1:1 Parity with Java's AnimationController & KeyframeTimeline (Forward Kinematics Matrix System)
 */
class EXeaAnimationEngine {
    constructor() {
        this.activeTimeline = null;
        this.currentTime = 0.0;
        this.speed = 1.0;
        this.isPlaying = false;
        this.onTick = null;
        this.onEvent = null;
        this.onFinish = null;
        this.triggeredEvents = new Set();
    }

    setSpeed(speed) {
        this.speed = parseFloat(speed) || 1.0;
    }

    setTimeline(timeline) {
        this.activeTimeline = timeline;
        this.currentTime = 0.0;
        this.triggeredEvents.clear();
    }

    play() {
        if (!this.activeTimeline) return;
        this.isPlaying = true;
    }

    pause() {
        this.isPlaying = false;
    }

    stop() {
        this.isPlaying = false;
        this.currentTime = 0.0;
        this.triggeredEvents.clear();
        if (this.onTick) this.onTick(this.currentTime);
    }

    seek(time) {
        if (!this.activeTimeline) return;
        this.currentTime = Math.max(0, Math.min(this.activeTimeline.length, time));
        if (this.onTick) this.onTick(this.currentTime);
    }

    step(dt) {
        if (!this.activeTimeline) return;
        let newTime = this.currentTime + dt;
        if (newTime > this.activeTimeline.length) {
            newTime = this.activeTimeline.loop === 'loop' ? 0 : this.activeTimeline.length;
        } else if (newTime < 0) {
            newTime = this.activeTimeline.loop === 'loop' ? this.activeTimeline.length : 0;
        }
        this.seek(newTime);
    }

    update(deltaSeconds) {
        if (!this.isPlaying || !this.activeTimeline) return;

        this.currentTime += deltaSeconds * this.speed;

        // Check timeline events (sound, particle, hitbox)
        if (this.activeTimeline.events && this.activeTimeline.events.length > 0) {
            for (const evt of this.activeTimeline.events) {
                if (this.currentTime >= evt.time && !this.triggeredEvents.has(evt)) {
                    this.triggeredEvents.add(evt);
                    if (this.onEvent) this.onEvent(evt);
                }
            }
        }

        if (this.onTick) {
            this.onTick(this.currentTime);
        }

        // Loop handling
        if (this.currentTime >= this.activeTimeline.length) {
            switch (this.activeTimeline.loop) {
                case 'loop':
                    this.currentTime = 0.0;
                    this.triggeredEvents.clear();
                    break;
                case 'hold':
                    this.currentTime = this.activeTimeline.length;
                    this.isPlaying = false;
                    break;
                case 'once':
                default:
                    this.isPlaying = false;
                    this.currentTime = 0.0;
                    this.triggeredEvents.clear();
                    if (this.onFinish) this.onFinish(this.activeTimeline.name);
                    break;
            }
        }
    }

    /**
     * Evaluate position vector for a given bone at time t (Linear interpolation)
     */
    evaluatePosition(boneName, time) {
        if (!this.activeTimeline || !this.activeTimeline.bones[boneName]) {
            return new THREE.Vector3(0, 0, 0);
        }

        const kfs = this.activeTimeline.bones[boneName].filter(k => k.channel === 'position');
        if (kfs.length === 0) return new THREE.Vector3(0, 0, 0);
        if (kfs.length === 1 || time <= kfs[0].time) {
            const v = kfs[0].vector;
            return new THREE.Vector3(v[0], v[1], v[2]);
        }
        if (time >= kfs[kfs.length - 1].time) {
            const v = kfs[kfs.length - 1].vector;
            return new THREE.Vector3(v[0], v[1], v[2]);
        }

        for (let i = 0; i < kfs.length - 1; i++) {
            const cur = kfs[i];
            const next = kfs[i + 1];
            if (time >= cur.time && time <= next.time) {
                const dt = next.time - cur.time;
                const alpha = dt <= 0.0001 ? 0 : (time - cur.time) / dt;
                const v1 = new THREE.Vector3(cur.vector[0], cur.vector[1], cur.vector[2]);
                const v2 = new THREE.Vector3(next.vector[0], next.vector[1], next.vector[2]);
                return v1.lerp(v2, alpha);
            }
        }
        return new THREE.Vector3(0, 0, 0);
    }

    /**
     * Evaluate rotation quaternion for a given bone at time t (Slerp quaternion interpolation)
     */
    evaluateRotation(boneName, time) {
        if (!this.activeTimeline || !this.activeTimeline.bones[boneName]) {
            return new THREE.Quaternion(); // Identity
        }

        const kfs = this.activeTimeline.bones[boneName].filter(k => k.channel === 'rotation');
        if (kfs.length === 0) return new THREE.Quaternion();

        function eulerToQuat(vec) {
            const eul = new THREE.Euler(
                THREE.MathUtils.degToRad(parseFloat(vec[0]) || 0),
                THREE.MathUtils.degToRad(parseFloat(vec[1]) || 0),
                THREE.MathUtils.degToRad(parseFloat(vec[2]) || 0),
                'XYZ'
            );
            return new THREE.Quaternion().setFromEuler(eul);
        }

        if (kfs.length === 1 || time <= kfs[0].time) {
            return eulerToQuat(kfs[0].vector);
        }
        if (time >= kfs[kfs.length - 1].time) {
            return eulerToQuat(kfs[kfs.length - 1].vector);
        }

        for (let i = 0; i < kfs.length - 1; i++) {
            const cur = kfs[i];
            const next = kfs[i + 1];
            if (time >= cur.time && time <= next.time) {
                const dt = next.time - cur.time;
                const alpha = dt <= 0.0001 ? 0 : (time - cur.time) / dt;
                const q1 = eulerToQuat(cur.vector);
                const q2 = eulerToQuat(next.vector);
                return q1.slerp(q2, alpha);
            }
        }
        return new THREE.Quaternion();
    }

    /**
     * Evaluate scale vector for a given bone at time t (Linear interpolation)
     */
    evaluateScale(boneName, time) {
        if (!this.activeTimeline || !this.activeTimeline.bones[boneName]) {
            return new THREE.Vector3(1, 1, 1);
        }

        const kfs = this.activeTimeline.bones[boneName].filter(k => k.channel === 'scale');
        if (kfs.length === 0) return new THREE.Vector3(1, 1, 1);
        if (kfs.length === 1 || time <= kfs[0].time) {
            const v = kfs[0].vector;
            return new THREE.Vector3(v[0], v[1], v[2]);
        }
        if (time >= kfs[kfs.length - 1].time) {
            const v = kfs[kfs.length - 1].vector;
            return new THREE.Vector3(v[0], v[1], v[2]);
        }

        for (let i = 0; i < kfs.length - 1; i++) {
            const cur = kfs[i];
            const next = kfs[i + 1];
            if (time >= cur.time && time <= next.time) {
                const dt = next.time - cur.time;
                const alpha = dt <= 0.0001 ? 0 : (time - cur.time) / dt;
                const v1 = new THREE.Vector3(cur.vector[0], cur.vector[1], cur.vector[2]);
                const v2 = new THREE.Vector3(next.vector[0], next.vector[1], next.vector[2]);
                return v1.lerp(v2, alpha);
            }
        }
        return new THREE.Vector3(1, 1, 1);
    }
}

window.EXeaAnimationEngine = EXeaAnimationEngine;
