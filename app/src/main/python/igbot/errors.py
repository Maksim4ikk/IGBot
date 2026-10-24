from __future__ import annotations
from typing import Optional

class LoginRequired(Exception): pass
class FeedbackRequired(Exception): pass
class ChallengeRequired(Exception): pass
class PleaseWaitFewMinutes(Exception):
    def __init__(self, message: str, retry_after: Optional[int] = None):
        super().__init__(message)
        self.retry_after = retry_after
class UserNotFound(Exception): pass
class MediaNotFound(Exception): pass
class ApiError(Exception): pass          # неочікувана/порожня відповідь IG (не "користувача нема")
class StopWork(Exception): pass


