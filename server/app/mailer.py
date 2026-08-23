# -*- coding: utf-8 -*-
"""Invite emails, sent through a Gmail SMTP relay.

Stdlib only (`smtplib`, `email.message`), matching this project's existing
minimal-dependency style (see requirements.txt). Gmail's relay wants
STARTTLS on port 587 and an app password, not the account password itself --
see https://support.google.com/mail/answer/185833.
"""

import smtplib
from email.message import EmailMessage


def invite_configured(settings) -> bool:
    return bool(settings.smtp_user and settings.smtp_app_password)


def _body(settings, token: str) -> str:
    lines = [
        "You've been invited to reed'd.",
        '',
        "Your access token (paste this into the app's Settings screen):",
        token,
        '',
    ]
    if settings.apk_path and settings.public_server_url:
        lines += [f'Download the app: {settings.public_server_url}/download/app', '']
    lines += [
        'Install the app, open Settings, and paste the token above into the '
        'API token field. The app already knows which server to talk to.',
    ]
    return '\n'.join(lines)


def send_invite(settings, to_email: str, token: str) -> None:
    """Send the invite email. Raises on any SMTP failure; the caller decides
    whether that's fatal -- see app.main.invite_user, which still creates the
    account and returns the token even if this raises."""
    message = EmailMessage()
    message['Subject'] = "You're invited to reed'd"
    message['From'] = settings.smtp_from
    message['To'] = to_email
    message.set_content(_body(settings, token))

    with smtplib.SMTP(settings.smtp_host, settings.smtp_port) as smtp:
        smtp.starttls()
        smtp.login(settings.smtp_user, settings.smtp_app_password)
        smtp.send_message(message)
