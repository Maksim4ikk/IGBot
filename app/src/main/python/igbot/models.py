from __future__ import annotations
from dataclasses import dataclass
from datetime import datetime
from typing import Optional

@dataclass
class UserShort:
    pk: int
    username: str
    full_name: str = ""
    is_private: Optional[bool] = None


@dataclass
class UserInfo:
    pk: int
    username: str
    full_name: str
    biography: str
    is_private: bool
    is_business: bool
    category: str
    media_count: int
    follower_count: int
    following_count: int


@dataclass
class MediaItem:
    id: str
    pk: int
    code: str
    taken_at: datetime
    user: Optional[UserShort] = None


@dataclass
class StoryItem:
    pk: int
    user_pk: int
    taken_at: int


